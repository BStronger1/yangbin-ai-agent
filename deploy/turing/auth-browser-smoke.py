"""Full registration and login browser acceptance; no paid model requests.
Only generated QA users/invites/documents are removed after this run.
"""
import hashlib,json,secrets,uuid,urllib.parse
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from accounts import Accounts
ROOT=Path(__file__).resolve().parents[2]
e=json.loads((ROOT/'secrets.json').read_text())['env'];base='http://'+e['PUBLIC_BIND']+':'+e['PUBLIC_PORT']
out=ROOT/'logs/screenshots';out.mkdir(exist_ok=True)
qa_users=[];qa_codes=[];qa_documents=[];errors=[]
password='Qa-'+secrets.token_urlsafe(18);new_password='QaNew-'+secrets.token_urlsafe(18)
accounts=Accounts(ROOT/'tmp/auth/accounts.sqlite',e['ACCESS_USER'],e['ACCESS_PASSWORD'])
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=str(Path.home()/'apps/agent-workbench/runtime/browsers/chromium-1194/chrome-linux/chrome'),headless=True,args=['--no-sandbox'])
 def context():
  c=browser.new_context(viewport={'width':1360,'height':900},locale='zh-CN',timezone_id='Asia/Shanghai')
  c.on('page',lambda page:page.on('pageerror',lambda error:errors.append(str(error))))
  return c
 def api(c,path,method='GET',data=None):
  user=c.request.get(base+'/api/auth/me').json().get('user')
  headers={'Origin':base}
  if user:headers['X-CSRF-Token']=user['csrf']
  return c.request.fetch(base+path,method=method,data=data,headers=headers)
 def login(page,name,pwd):
  page.goto(base+'/login',wait_until='networkidle');page.get_by_label('用户名',exact=True).fill(name);page.get_by_label('密码',exact=True).fill(pwd);page.get_by_role('button',name='登录',exact=True).click()
 def register(page,name,code):
  page.goto(base+'/register',wait_until='networkidle');page.get_by_label('用户名',exact=True).fill(name);page.get_by_label('密码',exact=True).fill(password);page.get_by_label('确认密码',exact=True).fill(password);page.get_by_label('邀请码',exact=True).fill(code);page.get_by_role('button',name='注册并进入工作台',exact=True).click()
 try:
  admin=context();page=admin.new_page();page.goto(base+'/knowledge',wait_until='networkidle');expect(page.get_by_role('heading',name='欢迎回来',exact=True)).to_be_visible()
  assert '/login' in page.url
  page.screenshot(path=str(out/'login.png'),full_page=True)
  page.set_viewport_size({'width':390,'height':844});page.screenshot(path=str(out/'login-mobile.png'),full_page=True);page.set_viewport_size({'width':1360,'height':900})
  login(page,e['ACCESS_USER'],e['ACCESS_PASSWORD'])
  expect(page.get_by_role('button',name='退出',exact=True)).to_be_visible(timeout=20000)
  assert api(admin,'/api/ai/capabilities').json()['terminal'] is True
  old_plans=api(admin,'/api/ai/plans').json();assert old_plans,'Legacy administrator plans missing'
  page.goto(base+'/account',wait_until='networkidle');page.get_by_role('button',name='生成邀请码',exact=True).click()
  code=page.get_by_label('本次邀请码（点击选中复制）').input_value();assert code;qa_codes.append(code)
  a=context();pa=a.new_page();pa.goto(base+'/register',wait_until='networkidle');pa.screenshot(path=str(out/'register.png'),full_page=True)
  name_a='qa_'+uuid.uuid4().hex[:10];register(pa,name_a,code);expect(pa.get_by_role('button',name='退出',exact=True)).to_be_visible(timeout=20000)
  ua=api(a,'/api/auth/me').json()['user'];qa_users.append((ua['id'],name_a))
  assert ua['role']=='user' and api(a,'/api/ai/capabilities').json()['terminal'] is False
  assert api(a,'/api/auth/invites').status==403
  assert api(a,'/api/ai/plans').json()==[]
  assert api(a,'/api/ai/plans/'+old_plans[0]['id']).json()==[]
  assert api(a,'/api/ai/plans/'+old_plans[0]['id']+'/1/pdf','POST').status==400
  legacy_pdf='/api/files/plan-'+old_plans[0]['id']+'-v2.pdf'
  assert api(a,legacy_pdf).status==404
  pa.goto(base+'/knowledge',wait_until='networkidle')
  pa.get_by_label('选择文件').set_input_files({'name':'qa-private.md','mimeType':'text/markdown','buffer':'私人紫色流星基地7391观星约会建议携带双筒望远镜。'.encode()})
  pa.get_by_role('button',name='上传并建立索引',exact=True).click();expect(pa.locator('button.item')).to_have_count(4,timeout=30000)
  private=next(d for d in api(a,'/api/ai/knowledge').json() if not d['builtin']);qa_documents.append((private['id'],ua['id']))
  query=urllib.parse.urlencode({'query':'私人紫色流星基地7391观星约会要准备什么'})
  assert any(x['source']=='qa-private.md' for x in api(a,'/api/ai/rag/search?'+query).json())
  b=context();pb=b.new_page();name_b='qa_'+uuid.uuid4().hex[:10];register(pb,name_b,code);expect(pb.get_by_role('alert')).to_contain_text('邀请码无效',timeout=15000)
  invite=api(admin,'/api/auth/invites','POST',{'maxUses':1,'days':1}).json();qa_codes.append(invite['code']);pb.get_by_label('邀请码',exact=True).fill(invite['code']);pb.get_by_role('button',name='注册并进入工作台',exact=True).click();expect(pb.get_by_role('button',name='退出',exact=True)).to_be_visible(timeout=20000)
  ub=api(b,'/api/auth/me').json()['user'];qa_users.append((ub['id'],name_b))
  assert len(api(b,'/api/ai/knowledge').json())==3
  assert api(b,'/api/ai/knowledge/'+private['id']).status==400
  assert api(b,'/api/ai/knowledge/'+private['id'],'DELETE').status==400
  assert all(x['source']!='qa-private.md' for x in api(b,'/api/ai/rag/search?'+query).json())
  assert b.request.get(base+'/api/ai/plans',headers={'X-User-Id':'admin','X-User-Role':'admin'}).json()==[]
  pa.goto(base+'/account',wait_until='networkidle');expect(pa.get_by_role('heading',name='邀请新用户')).to_have_count(0)
  pa.get_by_label('当前密码',exact=True).fill(password);pa.get_by_label('新密码',exact=True).fill(new_password);pa.get_by_label('确认新密码',exact=True).fill(new_password);pa.get_by_role('button',name='更新密码',exact=True).click();expect(pa.get_by_role('heading',name='欢迎回来',exact=True)).to_be_visible(timeout=20000)
  login(pa,name_a,password);expect(pa.get_by_role('alert')).to_contain_text('用户名或密码错误',timeout=20000)
  pa.get_by_label('密码',exact=True).fill(new_password);pa.get_by_role('button',name='登录',exact=True).click();expect(pa.get_by_role('button',name='退出',exact=True)).to_be_visible(timeout=20000)
  assert api(a,'/api/ai/knowledge/'+private['id'],'DELETE').status==200;qa_documents.clear()
  page.reload(wait_until='networkidle');row=page.locator('article.item').filter(has_text=name_b);row.get_by_role('button',name='停用账号',exact=True).click();expect(row.get_by_text('已停用',exact=True)).to_be_visible(timeout=15000)
  assert api(b,'/api/ai/knowledge').status==401
  pa.get_by_role('button',name='退出',exact=True).click();expect(pa.get_by_role('heading',name='欢迎回来',exact=True)).to_be_visible(timeout=10000);assert api(a,'/api/ai/knowledge').status==401
  # Admin UI and registration form also fit narrow screens.
  page.set_viewport_size({'width':390,'height':844});page.reload(wait_until='networkidle');assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
  page.screenshot(path=str(out/'account-mobile.png'),full_page=True)
  pb.goto(base+'/register',wait_until='networkidle');pb.set_viewport_size({'width':390,'height':844});assert pb.evaluate('document.documentElement.scrollWidth <= innerWidth');pb.screenshot(path=str(out/'register-mobile.png'),full_page=True)
  assert not errors,errors
  report={key:True for key in ('login','registration','single_use_invite','logout','password_change','disable_account','private_knowledge','private_plans_and_pdf','role_restrictions','spoofed_headers_rejected','legacy_admin_data','mobile')};report['paid_model_calls']=0
  (ROOT/'logs/auth-verification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
 finally:
  # Delete only IDs created in this run; never operate on existing user data.
  for doc,owner in qa_documents:
   import urllib.request
   request=urllib.request.Request('http://127.0.0.1:18126/api/ai/knowledge/'+doc,method='DELETE',headers={'X-Internal-Token':e['INTERNAL_API_TOKEN'],'X-User-Id':owner,'X-User-Role':'user'})
   try:urllib.request.urlopen(request,timeout=10).close()
   except Exception:pass
  with accounts.db() as db:
   for id,name in qa_users:
    if name.startswith('qa_') and db.execute('SELECT 1 FROM users WHERE id=? AND username=? AND role=?',(id,name,'user')).fetchone():
     db.execute('DELETE FROM sessions WHERE user_id=?',(id,));db.execute('DELETE FROM users WHERE id=?',(id,))
   for code in qa_codes:db.execute('DELETE FROM invites WHERE code=?',(hashlib.sha256(code.encode()).hexdigest(),))
  browser.close()

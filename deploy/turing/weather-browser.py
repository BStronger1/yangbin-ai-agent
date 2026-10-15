"""Beijing live forecast acceptance and a simulated browser failure/retry; no model calls."""
from pathlib import Path
import datetime,json
from playwright.sync_api import sync_playwright,expect
ROOT=Path(__file__).resolve().parents[2];e=json.loads((ROOT/'secrets.json').read_text())['env'];base='http://'+e['PUBLIC_BIND']+':'+e['PUBLIC_PORT']
date=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date().isoformat()
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=str(Path.home()/'apps/agent-workbench/runtime/browsers/chromium-1194/chrome-linux/chrome'),headless=True,args=['--no-sandbox'])
 ctx=browser.new_context(viewport={'width':1360,'height':960},timezone_id='Asia/Shanghai');assert ctx.request.post(base+'/api/auth/login',headers={'Origin':base},data={'username':e['ACCESS_USER'],'password':e['ACCESS_PASSWORD']}).status==200
 page=ctx.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
 page.goto(base+'/planner',wait_until='networkidle');page.get_by_label('中国城市',exact=True).fill('北京');page.get_by_label('日期',exact=True).fill(date)
 # Simulate only the first browser response; the retry reaches the real deployed API.
 def fail_once(route):
  route.fulfill(status=200,content_type='application/json',body=json.dumps({'fetchedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'location':None,'weather':None,'places':[],'weatherStatus':'unavailable','placesStatus':'unavailable','notices':['浏览器验收模拟一次天气失败']}))
 page.route('**/api/ai/plans/evidence?*',fail_once,times=1)
 page.get_by_role('button',name='查询天气与地点依据',exact=True).click()
 expect(page.get_by_role('button',name='重新查询天气与地点',exact=True)).to_be_visible()
 with page.expect_response(lambda response:'/api/ai/plans/evidence?' in response.url,timeout=90000) as pending:
  page.get_by_role('button',name='重新查询天气与地点',exact=True).click()
 data=pending.value.json();assert data['weatherStatus']=='available',data['notices'];assert data['weather']['date']==date
 assert data['placesStatus']=='available';assert e['AMAP_API_KEY'] not in json.dumps(data)
 expect(page.get_by_text('天气预报 · 已获取',exact=True)).to_be_visible()
 expect(page.get_by_text('本次查询结果',exact=True)).to_be_visible()
 expect(page.get_by_role('button',name='重新查询天气与地点',exact=True)).to_have_count(0)
 page.set_viewport_size({'width':390,'height':844});assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 page.screenshot(path=str(ROOT/'logs/screenshots/beijing-weather-fixed.png'),full_page=True)
 legacy=False
 if page.locator('button.item').count():
  page.locator('button.item').first.click();expect(page.get_by_text('已保存方案的历史依据',exact=True)).to_be_visible();expect(page.get_by_text('本次查询结果',exact=True)).to_have_count(0);legacy=True
 assert not errors,errors
 report={'city':'北京','date':date,'weather':data['weather'],'places':len(data['places']),'simulated_failure_retry_ui':True,'live_retry_succeeded':True,'snapshot_label':legacy,'mobile':True,'js_errors':errors}
 (ROOT/'logs/beijing-weather-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False));browser.close()

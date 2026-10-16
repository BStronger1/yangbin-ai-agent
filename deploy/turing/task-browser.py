"""Task workbench browser acceptance, including a real create/cancel interaction."""
from pathlib import Path
import json
from playwright.sync_api import sync_playwright, expect
ROOT=Path(__file__).resolve().parents[2]
env=json.loads((ROOT/'secrets.json').read_text())['env']
base='http://'+env['PUBLIC_BIND']+':'+env['PUBLIC_PORT']
state=json.loads((ROOT/'logs/task-v1-state.json').read_text(encoding='utf-8'))
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=str(Path.home()/'apps/agent-workbench/runtime/browsers/chromium-1194/chrome-linux/chrome'),headless=True,args=['--no-sandbox'])
 context=browser.new_context(viewport={'width':1360,'height':960},timezone_id='Asia/Shanghai')
 assert context.request.post(base+'/api/auth/login',headers={'Origin':base},data={'username':env['ACCESS_USER'],'password':env['ACCESS_PASSWORD']}).status==200
 page=context.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
 page.goto(base+'/tasks/'+state['id'],wait_until='networkidle')
 expect(page.get_by_test_id('task-status')).to_have_text('已完成')
 for step in ('requirements','knowledge','evidence','model','save','pdf'):
  expect(page.get_by_test_id('task-step-'+step)).to_contain_text('已完成')
 expect(page.get_by_role('link',name='下载 PDF',exact=True)).to_be_visible()
 assert context.request.get(page.get_by_role('link',name='下载 PDF',exact=True).get_attribute('href')).body().startswith(b'%PDF')
 page.reload(wait_until='networkidle');expect(page.get_by_test_id('task-status')).to_have_text('已完成')
 assert env['AMAP_API_KEY'] not in page.content()
 page.screenshot(path=str(ROOT/'logs/screenshots/task-v1-desktop.png'),full_page=True)
 page.set_viewport_size({'width':390,'height':844});assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
 page.screenshot(path=str(ROOT/'logs/screenshots/task-v1-mobile.png'),full_page=True)
 page.get_by_role('link',name='新建任务',exact=True).click()
 expect(page.get_by_label('任务描述',exact=True)).to_be_visible()
 page.get_by_label('任务描述',exact=True).fill('浏览器验收示例：提交后立即取消')
 page.get_by_label('日期',exact=True).fill(state['payload']['requirements']['date'])
 page.get_by_role('button',name='提交任务',exact=True).click()
 expect(page.get_by_role('button',name='取消任务',exact=True)).to_be_visible(timeout=30000)
 cancelled_id=page.url.rsplit('/',1)[-1]
 page.get_by_role('button',name='取消任务',exact=True).click()
 expect(page.get_by_test_id('task-status')).to_have_text('已取消',timeout=90000)
 expect(page.get_by_role('link',name='下载 PDF',exact=True)).to_have_count(0)
 page.reload(wait_until='networkidle');expect(page.get_by_test_id('task-status')).to_have_text('已取消')
 assert not errors,errors
 report={'task_id':state['id'],'six_steps':True,'pdf_download':True,'refresh_restored':True,'desktop':True,'mobile_no_overflow':True,'ui_create_cancel':True,'cancelled_task_id':cancelled_id,'no_secret_in_dom':True,'js_errors':errors}
 (ROOT/'logs/task-v1-browser.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False));browser.close()

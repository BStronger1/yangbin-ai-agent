"""Browser acceptance for skill CRUD, version conflicts, plan confirmation and legacy tasks."""
from pathlib import Path
import json
from playwright.sync_api import sync_playwright,expect
ROOT=Path(__file__).resolve().parents[2]
env=json.loads((ROOT/'secrets.json').read_text())['env'];base='http://'+env['PUBLIC_BIND']+':'+env['PUBLIC_PORT']
state=json.loads((ROOT/'logs/skills-v2-state.json').read_text(encoding='utf-8'))
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=str(Path.home()/'apps/agent-workbench/runtime/browsers/chromium-1194/chrome-linux/chrome'),headless=True,args=['--no-sandbox'],timeout=120000)
 ctx=browser.new_context(viewport={'width':1360,'height':960},timezone_id='Asia/Shanghai')
 login=ctx.request.post(base+'/api/auth/login',headers={'Origin':base},data={'username':env['ACCESS_USER'],'password':env['ACCESS_PASSWORD']});assert login.status==200
 auth=login.json()['user'];headers={'Origin':base,'X-CSRF-Token':auth['csrf']}
 page=ctx.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
 page.goto(base+'/skills',wait_until='networkidle')
 page.get_by_test_id('skill-builtin-city').get_by_role('button',name='复制技能',exact=True).click()
 page.get_by_label('技能名称',exact=True).fill('浏览器技能验收示例')
 page.get_by_label('执行指引',exact=True).fill('安排轻松的两人出行，控制步行强度，费用不超预算。')
 page.get_by_label('允许导出 PDF',exact=True).uncheck()
 with page.expect_response(lambda r:r.url.endswith('/api/ai/skills') and r.request.method=='POST') as pending:
  page.get_by_role('button',name='保存技能',exact=True).click()
 saved=pending.value.json();assert pending.value.status==200;id=saved['id'];card=page.get_by_test_id('skill-'+id)
 expect(card).to_be_visible()
 card.get_by_role('link',name='使用技能',exact=True).click()
 expect(page.get_by_label('选择技能',exact=True)).to_have_value(id)
 page.goto(base+'/skills',wait_until='networkidle');card=page.get_by_test_id('skill-'+id)
 card.get_by_role('button',name='编辑技能',exact=True).click()
 expect(page.get_by_label('技能名称',exact=True)).to_have_value('浏览器技能验收示例')
 page.get_by_label('技能简介',exact=True).fill('本地未保存的说明')
 # A real concurrent API update makes the browser editor version stale.
 changed=dict(saved,description='另一会话已更新此说明',enabled=True)
 external=ctx.request.put(base+'/api/ai/skills/'+id,headers=headers,data=changed);assert external.status==200
 page.get_by_role('button',name='保存技能',exact=True).click()
 expect(page.get_by_role('button',name='重新读取最新版本',exact=True)).to_be_visible()
 expect(page.get_by_label('技能简介',exact=True)).to_have_value('本地未保存的说明')
 page.get_by_role('button',name='重新读取最新版本',exact=True).click()
 expect(page.get_by_label('技能简介',exact=True)).to_have_value('另一会话已更新此说明')
 page.get_by_label('技能简介',exact=True).fill('读取最新版本后完成编辑')
 page.get_by_role('button',name='保存技能',exact=True).click()
 expect(card).to_contain_text('v3')
 card.get_by_role('button',name='停用技能',exact=True).click();expect(card).to_contain_text('已停用')
 expect(card.get_by_role('link',name='使用技能',exact=True)).to_have_count(0)
 card.get_by_role('button',name='启用技能',exact=True).click();expect(card).to_contain_text('已启用')
 page.screenshot(path=str(ROOT/'logs/screenshots/skills-v2-desktop.png'),full_page=True)
 page.set_viewport_size({'width':390,'height':844});assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 page.screenshot(path=str(ROOT/'logs/screenshots/skills-v2-mobile.png'),full_page=True)
 card.get_by_role('button',name='删除技能',exact=True).click();page.get_by_role('button',name='确认删除',exact=True).click();expect(card).to_have_count(0)
 print('Skill browser CRUD, conflict protection and mobile passed',flush=True)
 page.set_viewport_size({'width':1360,'height':960});page.goto(base+'/tasks/'+state['richId'],wait_until='networkidle')
 expect(page.get_by_test_id('task-status')).to_have_text('等待确认')
 for step in ('requirements','knowledge','evidence','model','save','pdf'):expect(page.get_by_test_id('planned-step-'+step)).to_be_visible()
 expect(page.get_by_role('link',name='下载 PDF',exact=True)).to_have_count(0)
 page.reload(wait_until='networkidle');expect(page.get_by_test_id('task-status')).to_have_text('等待确认')
 page.screenshot(path=str(ROOT/'logs/screenshots/skills-v2-plan-waiting.png'),full_page=True)
 page.get_by_role('button',name='确认并执行',exact=True).click()
 expect(page.get_by_test_id('task-status')).to_have_text('已完成',timeout=240000)
 expect(page.get_by_role('link',name='下载 PDF',exact=True)).to_be_visible()
 page.reload(wait_until='networkidle');expect(page.get_by_test_id('task-status')).to_have_text('已完成')
 assert env['AMAP_API_KEY'] not in page.content()
 page.screenshot(path=str(ROOT/'logs/screenshots/skills-v2-task-desktop.png'),full_page=True)
 page.set_viewport_size({'width':390,'height':844});assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 page.screenshot(path=str(ROOT/'logs/screenshots/skills-v2-task-mobile.png'),full_page=True)
 page.goto(base+'/tasks/'+state['minimalId'],wait_until='networkidle')
 expect(page.get_by_test_id('task-status')).to_have_text('已完成');expect(page.get_by_role('link',name='下载 PDF',exact=True)).to_have_count(0)
 legacy=json.loads((ROOT/'logs/task-v1-state.json').read_text(encoding='utf-8'))['id']
 page.goto(base+'/tasks/'+legacy,wait_until='networkidle');expect(page.get_by_test_id('task-status')).to_have_text('已完成')
 expect(page.get_by_test_id('execution-plan')).to_have_count(0)
 assert not errors,errors
 report={'skill_copy_create_edit':True,'skill_task_preselection':True,'real_version_conflict_preserves_draft':True,'disable_enable_delete':True,'waiting_refresh':True,'ui_confirm_execute':True,'completed_refresh':True,'pdf_link':True,'minimal_no_pdf':True,'legacy_task_compatible':True,'mobile_no_overflow':True,'no_secret_in_dom':True,'js_errors':errors,'rich_task':state['richId']}
 (ROOT/'logs/skills-v2-browser.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False),flush=True);browser.close()

"""Browser acceptance of persisted conversations and sourced plans; no model calls."""
from pathlib import Path
import json
from playwright.sync_api import sync_playwright,expect
ROOT=Path(__file__).resolve().parents[2];e=json.loads((ROOT/'secrets.json').read_text())['env'];s=json.loads((ROOT/'logs/memory-live-state.json').read_text());base='http://'+e['PUBLIC_BIND']+':'+e['PUBLIC_PORT']
out=ROOT/'logs/screenshots';out.mkdir(exist_ok=True)
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=str(Path.home()/'apps/agent-workbench/runtime/browsers/chromium-1194/chrome-linux/chrome'),headless=True,args=['--no-sandbox'])
 context=browser.new_context(viewport={'width':1360,'height':960});assert context.request.post(base+'/api/auth/login',headers={'Origin':base},data={'username':e['ACCESS_USER'],'password':e['ACCESS_PASSWORD']}).status==200
 page=context.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
 page.goto(base+'/love-master',wait_until='networkidle');page.get_by_label('历史会话',exact=True).select_option(s['chatId']);expect(page.locator('.chat-area').get_by_text(s['marker'],exact=False).first).to_be_visible(timeout=10000);page.reload(wait_until='networkidle');expect(page.locator('.chat-area').get_by_text(s['marker'],exact=False).first).to_be_visible(timeout=10000);page.screenshot(path=str(out/'persistent-chat.png'),full_page=True)
 page.goto(base+'/planner',wait_until='networkidle');page.locator('button.item').first.click();expect(page.get_by_role('heading',name='规划依据',exact=True)).to_be_visible();expect(page.get_by_text('天气预报 · 已获取')).to_be_visible();expect(page.get_by_text('候选地点 · 已获取')).to_be_visible();page.screenshot(path=str(out/'live-planning.png'),full_page=True)
 page.get_by_role('button',name='新建',exact=True).click();page.get_by_label('日期',exact=True).fill(s['date']);page.get_by_role('button',name='查询天气与地点依据',exact=True).click();expect(page.get_by_text('候选地点 · 已获取')).to_be_visible(timeout=65000)
 page.set_viewport_size({'width':390,'height':844});assert page.evaluate('document.documentElement.scrollWidth<=innerWidth');page.screenshot(path=str(out/'live-planning-mobile.png'),full_page=True)
 assert not errors,errors;print(json.dumps({'history_restore':True,'reload_restore':True,'saved_plan_sources':True,'live_preview':True,'mobile':True,'js_errors':errors}));browser.close()

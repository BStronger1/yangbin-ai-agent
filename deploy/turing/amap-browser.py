"""Read-only browser check for live planning sources and legacy plan rendering."""
from pathlib import Path
import datetime,json
from playwright.sync_api import sync_playwright,expect
ROOT=Path(__file__).resolve().parents[2];e=json.loads((ROOT/'secrets.json').read_text())['env'];base='http://'+e['PUBLIC_BIND']+':'+e['PUBLIC_PORT']
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=str(Path.home()/'apps/agent-workbench/runtime/browsers/chromium-1194/chrome-linux/chrome'),headless=True,args=['--no-sandbox'])
 context=browser.new_context(viewport={'width':1360,'height':960});assert context.request.post(base+'/api/auth/login',headers={'Origin':base},data={'username':e['ACCESS_USER'],'password':e['ACCESS_PASSWORD']}).status==200
 page=context.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
 page.goto(base+'/planner',wait_until='networkidle')
 legacy=False
 if page.locator('button.item').count():
  page.locator('button.item').first.click();expect(page.get_by_role('button',name='导出 PDF',exact=True)).to_be_visible();legacy=True
 page.get_by_role('button',name='新建',exact=True).click()
 date=(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()+datetime.timedelta(days=1)).isoformat()
 page.get_by_label('日期',exact=True).fill(date);page.get_by_role('button',name='查询天气与地点依据',exact=True).click()
 expect(page.get_by_text('候选地点 · 已获取')).to_be_visible(timeout=90000)
 expect(page.get_by_text('天气预报 · 已获取')).to_be_visible()
 evidence=page.locator('.evidence-panel');text=evidence.inner_text();assert e['AMAP_API_KEY'] not in page.content()
 if '高德暂不可用' in text:assert '回退' in text and 'OpenStreetMap' in text
 else:assert '高德地图' in text
 evidence.locator('summary').click();expect(evidence.locator('article').first).to_be_visible()
 page.set_viewport_size({'width':390,'height':844});assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 page.screenshot(path=str(ROOT/'logs/screenshots/amap-planning-mobile.png'),full_page=True)
 assert not errors,errors
 print(json.dumps({'legacy_plan_rendered':legacy,'live_evidence_rendered':True,'source_or_fallback_visible':True,'mobile':True,'no_secret_in_dom':True,'js_errors':errors}));browser.close()

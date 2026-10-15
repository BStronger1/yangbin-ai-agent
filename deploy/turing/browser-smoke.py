"""Browser rendering and UI streaming checks. Model calls are intercepted locally."""
import json,re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
ROOT=Path(__file__).resolve().parents[2]
e=json.loads((ROOT/"secrets.json").read_text())["env"]
base="http://"+e["PUBLIC_BIND"]+":"+e["PUBLIC_PORT"]
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=str(Path.home()/"apps/agent-workbench/runtime/browsers/chromium-1194/chrome-linux/chrome"),headless=True,args=["--no-sandbox"])
    context=browser.new_context(http_credentials={"username":e["ACCESS_USER"],"password":e["ACCESS_PASSWORD"]},viewport={"width":1360,"height":900})
    context.route(re.compile(r"/api/ai/(love_app|manus)/chat"), lambda route: route.fulfill(status=200,content_type="text/event-stream",body="data: browser-stream-ok\n\ndata: [DONE]\n\n"))
    page=context.new_page();errors=[]
    page.on("pageerror",lambda error: errors.append(str(error)))
    assert page.goto(base,wait_until="domcontentloaded").status==200
    expect(page.locator("h1")).to_contain_text("yangbin")
    (ROOT/"logs/screenshots").mkdir(exist_ok=True)
    page.screenshot(path=str(ROOT/"logs/screenshots/home.png"),full_page=True,timeout=15000)
    for route in ("/love-master","/super-agent"):
        page.goto(base+route,wait_until="domcontentloaded")
        expect(page.locator("textarea")).to_be_visible()
        if route=="/love-master":
            expect(page.locator("select")).to_have_value("rag")
            assert not page.locator('option[value="mcp"]').is_disabled()
        page.locator("textarea").fill("browser UI smoke test")
        page.get_by_role("button",name="发送",exact=True).click()
        expect(page.get_by_text("browser-stream-ok",exact=True)).to_be_visible(timeout=10000)
        expect(page.locator("textarea")).to_be_enabled()
        page.screenshot(path=str(ROOT/"logs/screenshots"/(route[1:]+".png")),full_page=True,timeout=15000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator("textarea")).to_be_visible()
    page.set_viewport_size({"width":390,"height":844})
    page.goto(base,wait_until="domcontentloaded")
    expect(page.locator("h1")).to_be_visible()
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Mobile horizontal overflow"
    page.screenshot(path=str(ROOT/"logs/screenshots/mobile.png"),full_page=True,timeout=15000)
    assert not errors,errors
    print(json.dumps({"home":True,"chat_ui_streaming":True,"agent_ui_streaming":True,"route_refresh":True,"mobile":True,"js_errors":errors,"model_calls":0}))
    browser.close()

"""Browser checks of deployed pages; no chat model calls are made by this script."""
import json,re
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
ROOT=Path(__file__).resolve().parents[2]
e=json.loads((ROOT/"secrets.json").read_text())["env"]
base="http://"+e["PUBLIC_BIND"]+":"+e["PUBLIC_PORT"]
out=ROOT/"logs/screenshots";out.mkdir(exist_ok=True)
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=str(Path.home()/"apps/agent-workbench/runtime/browsers/chromium-1194/chrome-linux/chrome"),headless=True,args=["--no-sandbox"])
 context=browser.new_context(http_credentials={"username":e["ACCESS_USER"],"password":e["ACCESS_PASSWORD"]},viewport={"width":1360,"height":1000})
 page=context.new_page();errors=[];page.on("pageerror",lambda e:errors.append(str(e)))
 page.goto(base,wait_until="networkidle");expect(page.locator("h1")).to_contain_text("yangbin")
 page.goto(base+"/knowledge",wait_until="networkidle");expect(page.get_by_role("heading",name="知识库",exact=True)).to_be_visible()
 expect(page.locator("button.item")).to_have_count(3)
 page.get_by_label("输入一个问题").fill("婚后如何分担家务")
 page.get_by_role("button",name="检索知识库",exact=True).click();expect(page.locator(".result").first).to_be_visible(timeout=20000)
 page.locator("button.item").first.click();expect(page.locator("pre")).to_be_visible()
 page.screenshot(path=str(out/"knowledge.png"),full_page=True)
 page.get_by_label("选择文件").set_input_files({"name":"browser-upload-test.md","mimeType":"text/markdown","buffer":"紫色流星基地7391观星约会建议携带望远镜".encode()})
 page.get_by_role("button",name="上传并建立索引",exact=True).click()
 expect(page.locator("button.item")).to_have_count(4,timeout=30000)
 expect(page.locator("pre")).to_contain_text("紫色流星基地7391")
 page.once("dialog",lambda dialog:dialog.accept())
 page.get_by_role("button",name="删除文档",exact=True).click()
 expect(page.locator("button.item")).to_have_count(3,timeout=10000)
 page.goto(base+"/mcp",wait_until="networkidle");page.locator("button.item").filter(has_text="currentTime").click()
 page.get_by_role("button",name="运行测试",exact=True).click();expect(page.get_by_text("调用成功",exact=True)).to_be_visible(timeout=20000)
 page.screenshot(path=str(out/"mcp.png"),full_page=True)
 page.goto(base+"/planner",wait_until="networkidle");expect(page.get_by_role("heading",name="约会规划",exact=True)).to_be_visible()
 report_file=ROOT/"logs/workspace-verification.json"
 if report_file.exists() and json.loads(report_file.read_text()).get("planner"):
  page.locator("button.item").first.click();expect(page.get_by_label("方案版本")).to_have_value("2")
  page.get_by_label("方案版本").select_option("1");expect(page.get_by_label("两人总预算（元）")).to_have_value("500")
  expect(page.get_by_role("button",name="保存为新版本",exact=True)).to_be_disabled()
  page.get_by_label("方案版本").select_option("2");expect(page.get_by_label("两人总预算（元）")).to_have_value("250")
  page.get_by_role("button",name="导出 PDF",exact=True).click();expect(page.get_by_role("link",name="打开 PDF",exact=True)).to_be_visible(timeout=20000)
 page.screenshot(path=str(out/"planner.png"),full_page=True)
 # Synthetic SSE is used only for browser event rendering; Java integration tests cover the actual event producer.
 event={"id":"browser-trace","type":"retrieval","name":"知识库检索","status":"completed","durationMs":12,"sources":[{"source":"浏览器测试资料","score":0.8,"content":"仅用于 UI 验收"}]}
 context.route(re.compile(r"/api/ai/(love_app|manus)/chat"),lambda route:route.fulfill(status=200,content_type="text/event-stream",body="event: trace\ndata: "+json.dumps(event,ensure_ascii=False)+"\n\ndata: browser-stream-ok\n\ndata: [DONE]\n\n"))
 for route in ("/love-master","/super-agent"):
  page.goto(base+route,wait_until="networkidle");page.locator("textarea").fill("browser test")
  page.get_by_role("button",name="发送",exact=True).click()
  expect(page.get_by_text("browser-stream-ok",exact=True)).to_be_visible(timeout=10000)
  expect(page.get_by_role("heading",name="本次执行记录",exact=True)).to_be_visible()
  page.locator(".trace-panel summary").click();expect(page.get_by_text("[资料1] 浏览器测试资料",exact=True)).to_be_visible()
  expect(page.locator("textarea")).to_be_enabled()
 for route in ("/knowledge","/mcp","/planner"):
  page.set_viewport_size({"width":390,"height":844});page.goto(base+route,wait_until="networkidle")
  assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"),route+" mobile overflow"
  page.evaluate("window.scrollTo(0, document.documentElement.scrollHeight)")
  page.wait_for_timeout(100)
  assert page.evaluate("window.scrollY > 0"),route+" cannot scroll document"
  assert page.locator(".workspace .panel").last.bounding_box()["y"] < 844,route+" final panel inaccessible"
  page.evaluate("window.scrollTo(0,0)")
  page.screenshot(path=str(out/(route[1:]+"-mobile.png")),full_page=True)
  page.reload(wait_until="networkidle");expect(page.locator("h1")).to_be_visible()
 assert not errors,errors
 print(json.dumps({"knowledge":True,"mcp":True,"planner":True,"stream_trace_rendering":True,"mobile":True,"route_refresh":True,"js_errors":errors,"paid_model_calls":0}))
 browser.close()

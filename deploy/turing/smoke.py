"""Real-model smoke check; never prints credentials or provider responses."""
import base64,json,urllib.request,urllib.parse,urllib.error,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
e=json.loads((ROOT/"secrets.json").read_text())["env"]
base="http://"+e["PUBLIC_BIND"]+":"+e["PUBLIC_PORT"]
from session_client import SessionClient
session=SessionClient(base,e['ACCESS_USER'],e['ACCESS_PASSWORD'])
def get(path):return session.call(path).decode()
try:
    urllib.request.urlopen(base+"/api/health",timeout=10)
    raise AssertionError("Unauthenticated endpoint exposed")
except urllib.error.HTTPError as error: assert error.code==401
assert get("/api/health")=="ok"
assert 'yangbin' in get('/').lower()
chat=get('/api/ai/love_app/chat/sse?'+urllib.parse.urlencode({"message":"请用一句中文介绍你能提供的帮助。","chatId":"smoke-"+uuid.uuid4().hex}))
assert '[DONE]' in chat and len(chat)>30 and '执行错误' not in chat
name="smoke-"+uuid.uuid4().hex+".pdf"
agent=get('/api/ai/manus/chat?'+urllib.parse.urlencode({"message":"请调用 PDF 工具生成名为 "+name+" 的文件，内容为：yangbin 智能体中文部署验收。完成后调用结束工具。"}))
assert '[DONE]' in agent and '执行错误' not in agent, 'Agent did not complete'
pdf=session.call('/api/files/'+name)
assert pdf.startswith(b'%PDF')
print(json.dumps({"authentication":True,"health":True,"frontend":True,"real_chat_sse":True,"agent_tool_pdf":True,"pdf_bytes":len(pdf)},ensure_ascii=False))

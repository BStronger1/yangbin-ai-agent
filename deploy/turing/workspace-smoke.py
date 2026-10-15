"""Real server acceptance. --model additionally makes two paid chat model requests."""
import base64,json,sys,time,urllib.request,urllib.error,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
e=json.loads((ROOT/"secrets.json").read_text())["env"]
base="http://"+e["PUBLIC_BIND"]+":"+e["PUBLIC_PORT"]
auth="Basic "+base64.b64encode((e["ACCESS_USER"]+":"+e["ACCESS_PASSWORD"]).encode()).decode()
def call(path,method="GET",data=None,content_type="application/json"):
 raw=None if data is None else data if isinstance(data,bytes) else json.dumps(data,ensure_ascii=False).encode()
 request=urllib.request.Request(base+path,data=raw,method=method,headers={"Authorization":auth,"Content-Type":content_type})
 with urllib.request.urlopen(request,timeout=180) as response:
  body=response.read()
  return json.loads(body) if "json" in response.headers.get("Content-Type","") else body
report={"paid_model_calls":0}
docs=call("/api/ai/knowledge");assert len(docs)>=3
report["documents"]=len(docs)
name="workspace-acceptance-"+uuid.uuid4().hex[:8]+".md"
boundary=uuid.uuid4().hex
text="观星约会测试资料：紫色流星基地编号7391，建议携带双筒望远镜和防寒衣物，提前预约观星时段。"
body=("--"+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: text/plain\r\n\r\n'+text+"\r\n--"+boundary+"--\r\n").encode()
upload=call("/api/ai/knowledge","POST",body,"multipart/form-data; boundary="+boundary)
try:
 query=urllib.parse.urlencode({"query":"紫色流星基地编号7391观星约会需要准备什么"})
 results=call("/api/ai/rag/search?"+query)
 assert any(s["source"]==name for s in results),"Uploaded source was not retrieved"
 assert upload["state"]=="READY";report["upload_and_retrieval"]=True
finally:call("/api/ai/knowledge/"+upload["id"],"DELETE")
assert all(d["id"]!=upload["id"] for d in call("/api/ai/knowledge"));report["delete"]=True
mcp=call("/api/ai/mcp");tool=next(t for t in mcp["tools"] if t["name"].endswith("currentTime"))
result=call("/api/ai/mcp/call","POST",{"name":tool["name"],"arguments":{"timezone":"Asia/Shanghai"}})
assert result["success"] and "+08:00" in result["result"];report["mcp"]=True
bad=call("/api/ai/mcp/call","POST",{"name":tool["name"],"arguments":{"timezone":"not-a-timezone"}})
assert bad["success"] is False;report["mcp_failure_reporting"]=True
report["retrieval_evaluation"]=call("/api/ai/knowledge/evaluate","POST")
if "--model" in sys.argv:
 req={"city":"上海","budget":500,"date":"2026-10-20","startTime":"14:00","hours":6,"interests":"安静交流、咖啡、室内文化活动，选择活动类型即可","transport":"公共交通","revision":""}
 plan=call("/api/ai/plans","POST",req);report["paid_model_calls"]+=1
 assert plan["version"]==1 and sum(s["estimatedCost"] for s in plan["itinerary"]["stops"])<=500
 req["budget"]=250;req["revision"]="预算减少到250元，改为下雨时可以执行的室内方案，所有价格均为估算。"
 revised=call("/api/ai/plans/"+plan["id"]+"/revise","POST",{"expectedVersion":1,"requirements":req});report["paid_model_calls"]+=1
 assert revised["version"]==2 and sum(s["estimatedCost"] for s in revised["itinerary"]["stops"])<=250
 versions=call("/api/ai/plans/"+plan["id"]);assert len(versions)==2 and versions[1]["requirements"]["budget"]==500
 pdf=call("/api/ai/plans/"+plan["id"]+"/2/pdf","POST");assert call(pdf["url"]).startswith(b"%PDF")
 report.update(plan_id=plan["id"],plan_version=2,pdf_url=pdf["url"],planner=True)
(ROOT/"logs/workspace-verification.json").write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False))

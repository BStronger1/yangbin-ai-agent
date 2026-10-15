"""Operator acceptance: prepare, restart externally, verify. Uses three real model calls total."""
from pathlib import Path
import json,sys,uuid,urllib.parse,datetime
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'deploy/turing'))
from session_client import SessionClient
e=json.loads((ROOT/'secrets.json').read_text())['env'];base='http://'+e['PUBLIC_BIND']+':'+e['PUBLIC_PORT']
c=SessionClient(base,e['ACCESS_USER'],e['ACCESS_PASSWORD']);state=ROOT/'logs/memory-live-state.json'
def stream(message,id):
 raw=c.call('/api/ai/love_app/chat/events?'+urllib.parse.urlencode({'message':message,'chatId':id,'mode':'chat'})).decode()
 assert '[DONE]' in raw and 'event:failure' not in raw,'Chat did not finish'
 return raw
if sys.argv[1]=='prepare':
 id='qa_memory_'+uuid.uuid4().hex[:12];marker='紫色海风'+uuid.uuid4().hex[:8]
 s={'chatId':id,'marker':marker};state.write_text(json.dumps(s))
 stream('这是一次会话记忆验收。请记住我的测试口令是'+marker+'。本轮只回复“已记录”。',id)
 history=c.call('/api/ai/conversations/'+id+'?mode=chat');assert any(marker in m['content'] for m in history)
 date=(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()+datetime.timedelta(days=1)).isoformat()
 evidence=c.call('/api/ai/plans/evidence?'+urllib.parse.urlencode({'city':'上海','date':date}));assert evidence['weatherStatus']=='available',evidence['notices'];assert evidence['placesStatus']=='available',evidence['notices']
 s['evidence']=evidence;s['date']=date;state.write_text(json.dumps(s))
 req={'city':'上海','budget':500,'date':date,'startTime':'14:00','hours':4,'interests':'散步与轻松交流；至少一站使用给定候选地点，附上对应 placeId。请遵守预报和出行前确认事项。','transport':'步行','revision':''}
 plan=c.call('/api/ai/plans','POST',req);s['planId']=plan['id'];state.write_text(json.dumps(s))
 assert plan['evidence']['weather'] and plan['evidence']['places'];assert any(stop.get('placeId') for stop in plan['itinerary']['stops'])
 url=c.call('/api/ai/plans/'+plan['id']+'/1/pdf','POST')['url'];pdf=c.call(url);assert pdf.startswith(b'%PDF');(ROOT/'logs/live-evidence-plan.pdf').write_bytes(pdf)
 print(json.dumps({'prepared':True,'weather':evidence['weatherStatus'],'places':len(evidence['places']),'saved_messages':len(history),'pdf':True}),flush=True)
elif sys.argv[1]=='verify':
 s=json.loads(state.read_text());history=c.call('/api/ai/conversations/'+s['chatId']+'?mode=chat');assert any(s['marker'] in m['content'] for m in history)
 answer=stream('我上一条消息告诉你的测试口令是什么？只输出口令，不要解释。',s['chatId']);assert s['marker'] in answer,'Model did not recover remembered value'
 plan=c.call('/api/ai/plans/'+s['planId'])[0];assert plan['evidence']==s['evidence'],'Saved evidence snapshot changed after restart'
 url=c.call('/api/ai/plans/'+s['planId']+'/1/pdf','POST')['url'];pdf=c.call(url);assert pdf.startswith(b'%PDF');(ROOT/'logs/live-evidence-plan.pdf').write_bytes(pdf)
 report={'restart_history':True,'restart_model_recall':True,'persistent_evidence_snapshot':True,'live_weather':True,'live_places':len(s['evidence']['places']),'real_model_plan':True,'pdf':True,'model_calls':3}
 (ROOT/'logs/memory-live-verification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
elif sys.argv[1]=='cleanup':
 s=json.loads(state.read_text());c.call('/api/ai/conversations/'+s['chatId']+'?mode=chat','DELETE')
 if s.get('planId'):
  import re
  assert re.fullmatch('[a-f0-9-]{36}',s['planId'])
  path=ROOT/'tmp/plans'/(s['planId']+'-1.json');plan=json.loads(path.read_text());assert plan['owner']=='admin' and plan['requirements']['interests'].startswith('散步与轻松交流；至少一站');path.unlink()
  (ROOT/'tmp/pdf/admin'/('plan-'+s['planId']+'-v1.pdf')).unlink(missing_ok=True)
 print('Only generated acceptance conversation and plan removed')

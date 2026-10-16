"""One real model-backed task: plan confirmation, frozen quality report and PDF.
prepare creates once and waits for UI confirmation; verify checks the completed result.
"""
from pathlib import Path
import datetime,json,sys,time,uuid
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'deploy/turing'))
from session_client import SessionClient
e=json.loads((ROOT/'secrets.json').read_text())['env']
c=SessionClient('http://'+e['PUBLIC_BIND']+':'+e['PUBLIC_PORT'],e['ACCESS_USER'],e['ACCESS_PASSWORD'])
state_path=ROOT/'logs/quality-v3-state.json'
def save(s):state_path.write_text(json.dumps(s,ensure_ascii=False,indent=2),encoding='utf-8')
def wait(id,status):
 deadline=time.monotonic()+300;last=None
 while time.monotonic()<deadline:
  t=c.call('/api/ai/tasks/'+id)
  if t['status']!=last:print(json.dumps({'task':id,'status':t['status']}),flush=True);last=t['status']
  if t['status']==status:return t
  if t['status'] in {'FAILED','CANCELLED','INTERRUPTED'}:raise AssertionError(t.get('error'))
  time.sleep(1)
 raise AssertionError('Task timeout')
if sys.argv[1]=='prepare':
 assert not state_path.exists(),'Existing acceptance state; do not create another paid task'
 date=(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()+datetime.timedelta(days=1)).isoformat()
 payload={'requestId':str(uuid.uuid4()),'skillId':'builtin-city','prompt':'规划北京两人室内出行。必须查天气地点、检索知识库、保存方案和导出PDF。每站完整写出对应候选地点名称，费用仅估算，不声称已确认营业、票价或路线耗时，不添加无依据的防疫政策。','requirements':{'city':'北京','date':date,'budget':500,'startTime':'14:00','hours':4,'interests':'咖啡与博物馆，至少一个地图候选地点','transport':'公共交通','revision':''}}
 save({'payload':payload})
 t=c.call('/api/ai/tasks','POST',payload);save({'payload':payload,'id':t['id']})
 t=wait(t['id'],'AWAITING_CONFIRMATION');assert t['quality'] is None and t['metrics']['toolCalls']==0
 assert any(x['id']=='pdf' for x in t['executionPlan']['steps'])
 print(json.dumps({'prepared':True,'id':t['id']}),flush=True)
elif sys.argv[1]=='verify':
 state=json.loads(state_path.read_text(encoding='utf-8'));t=wait(state['id'],'SUCCEEDED');q=t['quality']
 assert q and q['ruleVersion']=='grounding-v1' and q['limitations']
 assert q['totalStops']==len(t['itinerary']['stops']) and 0<=q['matchedStops']<=q['totalStops']
 assert q['status'] in {'CHECKED_WITH_LIMITATIONS','REVIEW_REQUIRED'}
 assert t['metrics']['modelCalls']==2 and t['pdfUrl']
 plans=c.call('/api/ai/plans/'+t['planId']);assert plans[0]['quality']==q
 assert c.call('/api/ai/tasks/'+t['id'])['quality']==q
 old=c.call('/api/ai/tasks/6679fd39-2fdb-4d40-9827-adbaa99a998b');assert old['status']=='SUCCEEDED' and old['quality'] is None
 pdf=c.call(t['pdfUrl']);assert pdf.startswith(b'%PDF');(ROOT/'logs/quality-v3-plan.pdf').write_bytes(pdf)
 report={'task':t['id'],'quality':q,'metrics':t['metrics'],'report_matches_plan':True,'refresh_preserved':True,'legacy_unchecked':True,'pdf':True,'weather_status':t['evidence']['weatherStatus'],'amap_places':sum(p['id'].startswith('amap/') for p in t['evidence']['places']),'itinerary':t['itinerary']}
 (ROOT/'logs/quality-v3-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False),flush=True)
else:raise SystemExit('Expected prepare or verify')

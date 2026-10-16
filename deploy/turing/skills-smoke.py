"""Normal-session skills/plan acceptance. Calls the configured model four times on success.
prepare runs one minimal task, prepares a rich task, and restarts only this backend.
Use skills-browser.py to confirm the rich task, then verify to collect the results.
"""
from pathlib import Path
import datetime,json,os,signal,sys,time,urllib.request,uuid
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'deploy/turing'))
from session_client import SessionClient

env=json.loads((ROOT/'secrets.json').read_text())['env']
base='http://'+env['PUBLIC_BIND']+':'+env['PUBLIC_PORT']
c=SessionClient(base,env['ACCESS_USER'],env['ACCESS_PASSWORD'])
state_path=ROOT/'logs/skills-v2-state.json'
def save(state):state_path.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
def wait_task(id,expected):
 deadline=time.monotonic()+300;last=None
 while time.monotonic()<deadline:
  task=c.call('/api/ai/tasks/'+id)
  value=(task['status'],tuple((x['id'],x['status']) for x in task['steps']))
  if value!=last:print(json.dumps({'task_id':id,'status':task['status'],'steps':[{k:x[k] for k in ('id','status')} for x in task['steps']]}),flush=True);last=value
  if task['status'] in expected:return task
  if task['status'] in {'FAILED','CANCELLED','INTERRUPTED'}:raise AssertionError('Unexpected task state: '+str(task.get('error')))
  time.sleep(1)
 raise AssertionError('Task acceptance timeout')
def restart_backend():
 matches=[]
 for proc in Path('/proc').iterdir():
  if not proc.name.isdigit():continue
  try:
   args=(proc/'cmdline').read_bytes().split(b'\0')
   if proc.stat().st_uid==os.getuid() and str(ROOT/'app.jar').encode() in args and b'-jar' in args:matches.append(int(proc.name))
  except (FileNotFoundError,PermissionError,ProcessLookupError):pass
 assert len(matches)==1,'Expected exactly one own backend process'
 pid=matches[0];os.kill(pid,signal.SIGTERM)
 for _ in range(120):
  if not Path('/proc/'+str(pid)).exists():break
  time.sleep(.5)
 else:raise AssertionError('Backend did not stop')
 deadline=time.monotonic()+300
 while time.monotonic()<deadline:
  try:
   with urllib.request.urlopen('http://127.0.0.1:18126/api/health',timeout=2) as response:
    if response.status==200:return
  except Exception:pass
  time.sleep(1)
 raise AssertionError('Supervisor did not restore backend')

if sys.argv[1]=='prepare':
 assert not state_path.exists(),'Acceptance state already exists; inspect before starting another paid run'
 date=(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()+datetime.timedelta(days=1)).isoformat()
 req={'city':'北京','date':date,'budget':500,'startTime':'14:00','hours':4,'interests':'咖啡、博物馆，至少一站使用地图候选并设置 placeId','transport':'公共交通','revision':''}
 state={'preparedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'requirements':req};save(state)
 skills=c.call('/api/ai/skills');assert {'builtin-city','builtin-rain'}<={x['id'] for x in skills}
 payload={'requestId':str(uuid.uuid4()),'skillId':'builtin-city','prompt':'验收示例：仅查询天气和地点并保存北京两人行程，不要检索知识库，不需要 PDF。执行计划不要包含 knowledge 和 pdf。','requirements':req}
 state['minimalPayload']=payload;save(state)
 minimal=c.call('/api/ai/tasks','POST',payload);state['minimalId']=minimal['id'];save(state)
 minimal=wait_task(minimal['id'],{'AWAITING_CONFIRMATION'})
 assert [x['id'] for x in minimal['executionPlan']['steps']]==['requirements','evidence','model','save']
 assert minimal['metrics']['modelCalls']==1 and minimal['metrics']['toolCalls']==0
 assert not minimal.get('planId') and not minimal.get('itinerary')
 c.call('/api/ai/tasks/'+minimal['id']+'/start','POST',{})
 c.call('/api/ai/tasks/'+minimal['id']+'/start','POST',{})
 minimal=wait_task(minimal['id'],{'SUCCEEDED'})
 assert not minimal.get('pdfUrl') and minimal['metrics']['modelCalls']==2
 assert minimal['sources']==[] and minimal['metrics']['toolCalls']==1
 state['minimalResult']={k:minimal[k] for k in ('id','status','metrics','executionPlan')};save(state)
 skill=c.call('/api/ai/skills','POST',{'name':'室内出行·验收示例','description':'结合个人资料、实时天气与地图，导出出行 PDF','instructions':'优先室内活动，根据实际天气说明理由，结合知识库里的沟通建议，用户需要时导出 PDF。','useKnowledge':True,'exportPdf':True})
 state['skillId']=skill['id'];save(state)
 rich_payload={'requestId':str(uuid.uuid4()),'skillId':skill['id'],'prompt':'验收示例：必须检索个人知识库，查询北京天气地点，生成并保存两人室内行程，最后导出 PDF。执行计划包含 knowledge 和 pdf。','requirements':req}
 state['richPayload']=rich_payload;save(state)
 rich=c.call('/api/ai/tasks','POST',rich_payload);state['richId']=rich['id'];save(state)
 rich=wait_task(rich['id'],{'AWAITING_CONFIRMATION'})
 assert [x['id'] for x in rich['executionPlan']['steps']]==['requirements','knowledge','evidence','model','save','pdf']
 assert rich['metrics']['modelCalls']==1 and rich['metrics']['toolCalls']==0
 changed=dict(skill,name='室内出行·更新版验收示例',instructions='这是更新后的技能；已创建任务应保持原来的第一版要求。',useKnowledge=False,exportPdf=False,enabled=False)
 updated=c.call('/api/ai/skills/'+skill['id'],'PUT',changed);assert updated['version']==2
 repeated=c.call('/api/ai/tasks','POST',rich_payload);assert repeated['id']==rich['id'] and repeated['skill']['version']==1
 restart_backend()
 restored=c.call('/api/ai/tasks/'+rich['id']);assert restored['status']=='AWAITING_CONFIRMATION' and restored['skill']['version']==1 and restored['confirmedAt'] is None
 assert restored['metrics']['modelCalls']==1 and restored['metrics']['toolCalls']==0
 state['waitingRestartRestored']=True;state['frozenSkillVersion']=1;state['currentSkillVersion']=2;save(state)
 print(json.dumps({'prepared':True,'minimal_task':minimal['id'],'rich_task':rich['id'],'waiting_restart_preserved':True,'frozen_skill_version':True,'confirmation_gate':True}),flush=True)
elif sys.argv[1]=='verify':
 state=json.loads(state_path.read_text(encoding='utf-8'))
 rich=wait_task(state['richId'],{'SUCCEEDED'})
 assert rich['skill']['version']==1 and rich['confirmedAt'] and rich['pdfUrl']
 assert len(rich['steps'])==7 and all(x['status']=='SUCCEEDED' for x in rich['steps'])
 assert rich['metrics']['modelCalls']==2 and rich['metrics']['toolCalls']==3
 assert rich['evidence']['weatherStatus']=='available' and rich['evidence']['placesStatus']=='available'
 assert any(x.get('placeId') for x in rich['itinerary']['stops'])
 pdf=c.call(rich['pdfUrl']);assert pdf.startswith(b'%PDF');(ROOT/'logs/skills-v2-plan.pdf').write_bytes(pdf)
 legacy_state=ROOT/'logs/task-v1-state.json';legacy_ok=False
 if legacy_state.exists():
  legacy_id=json.loads(legacy_state.read_text(encoding='utf-8'))['id'];old=c.call('/api/ai/tasks/'+legacy_id)
  assert old['status']=='SUCCEEDED' and len(old['steps'])==6 and old.get('skill') is None
  legacy_ok=True
 report={'minimal_task':state['minimalId'],'rich_task':rich['id'],'minimal_plan':state['minimalResult']['executionPlan'],'rich_plan':rich['executionPlan'],'waiting_restart_preserved':state['waitingRestartRestored'],'confirmation_gate':True,'skill_snapshot_version':rich['skill']['version'],'current_skill_version':2,'rich_metrics':rich['metrics'],'minimal_metrics':state['minimalResult']['metrics'],'real_weather':True,'amap_places':sum(x['id'].startswith('amap/') for x in rich['evidence']['places']),'pdf':True,'legacy_task_compatible':legacy_ok}
 (ROOT/'logs/skills-v2-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False),flush=True)
else:raise SystemExit('Expected prepare or verify')

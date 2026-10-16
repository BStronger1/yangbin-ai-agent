"""Task v1 operator acceptance. Uses normal login; may call the configured model.
prepare-restart creates one QA task and stops ONLY this application's tmux service.
Start deploy/turing/start.sh externally, then run resume to finish the same task.
"""
from pathlib import Path
import datetime,json,subprocess,sys,time,uuid
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'deploy/turing'))
from session_client import SessionClient
e=json.loads((ROOT/'secrets.json').read_text())['env'];base='http://'+e['PUBLIC_BIND']+':'+e['PUBLIC_PORT']
c=SessionClient(base,e['ACCESS_USER'],e['ACCESS_PASSWORD']);state=ROOT/'logs/task-v1-state.json'
terminal={'SUCCEEDED','FAILED','INTERRUPTED','CANCELLED'}
def save(s):state.write_text(json.dumps(s,ensure_ascii=False,indent=2),encoding='utf-8')
def wait_task(id):
 deadline=time.monotonic()+240;last=None
 while time.monotonic()<deadline:
  task=c.call('/api/ai/tasks/'+id)
  current=(task['status'],tuple((s['id'],s['status']) for s in task['steps']))
  if current!=last:print(json.dumps({'status':task['status'],'steps':[{k:s[k] for k in ('id','status')} for s in task['steps']]}),flush=True);last=current
  if task['status'] in terminal:return task
  time.sleep(1)
 raise AssertionError('Task did not settle within acceptance timeout')
mode=sys.argv[1]
if mode in ('create','prepare-restart'):
 requestId=str(uuid.uuid4());date=(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()+datetime.timedelta(days=1)).isoformat()
 payload={'requestId':requestId,'prompt':'验收示例：安排北京两人出行，考虑天气和资料，至少一站使用真实地图候选并保留来源，生成 PDF。','requirements':{'city':'北京','date':date,'budget':500,'startTime':'14:00','hours':4,'interests':'公园、咖啡与散步；至少一站来自地图候选并设置 placeId。','transport':'公共交通','revision':''}}
 s={'requestId':requestId,'payload':payload,'preparedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()};save(s)
 task=c.call('/api/ai/tasks','POST',payload);s['id']=task['id'];save(s)
 repeated=c.call('/api/ai/tasks','POST',payload);assert repeated['id']==task['id'];s['idempotent_create']=True;s['beforeStop']=c.call('/api/ai/tasks/'+task['id']);save(s)
 if mode=='prepare-restart':
  subprocess.run(['tmux','send-keys','-t','yangbin-ai-agent:0.0','C-c'],check=True)
  for _ in range(120):
   alive=subprocess.run(['tmux','has-session','-t','=yangbin-ai-agent'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
   if not alive:break
   time.sleep(.5)
  else:raise AssertionError('Application did not stop')
  print(json.dumps({'task_id':s['id'],'before_stop_status':s['beforeStop']['status'],'application_stopped':True,'idempotent_create':True}),flush=True);sys.exit(0)
elif mode=='resume':
 s=json.loads(state.read_text(encoding='utf-8'));task=c.call('/api/ai/tasks/'+s['id']);s['afterRestartStatus']=task['status'];save(s)
 if task['status']=='INTERRUPTED':c.call('/api/ai/tasks/'+s['id']+'/retry','POST',{})
 elif task['status'] not in ('QUEUED','RUNNING','SUCCEEDED'):raise AssertionError('Unexpected restart status: '+task['status'])
elif mode=='cancel-check':
 s=json.loads(state.read_text(encoding='utf-8'));payload=dict(s['payload'],requestId=str(uuid.uuid4()),prompt='验收示例：取消操作检查')
 task=c.call('/api/ai/tasks','POST',payload);c.call('/api/ai/tasks/'+task['id']+'/cancel','POST',{})
 task=wait_task(task['id']);assert task['status']=='CANCELLED';assert not task.get('pdfUrl')
 s['cancelledTaskId']=task['id'];save(s)
 print(json.dumps({'cancelled_task_id':task['id'],'cancelled':True,'pdf_not_created':True,'model_calls':task.get('metrics',{}).get('modelCalls')}),flush=True);sys.exit(0)
elif mode=='verify':s=json.loads(state.read_text(encoding='utf-8'))
else:raise SystemExit('Expected create, prepare-restart, resume or verify')
task=wait_task(s['id'])
# A real external-data outage is recorded, never converted to a passing model test.
assert task['status']=='SUCCEEDED','Task did not succeed: '+str(task.get('error'))
assert all(step['status']=='SUCCEEDED' for step in task['steps'])
assert task.get('pdfUrl') and task.get('planId')
pdf=c.call(task['pdfUrl']);assert pdf.startswith(b'%PDF');(ROOT/'logs/task-v1-plan.pdf').write_bytes(pdf)
again=c.call('/api/ai/tasks/'+s['id']);assert again['pdfUrl']==task['pdfUrl']
plan=c.call('/api/ai/plans/'+task['planId'])[0];assert plan['owner']=='admin';assert plan['evidence']['weather']
assert any(stop.get('placeId') for stop in plan['itinerary']['stops']), 'Plan did not select a sourced place'
report={'task_id':s['id'],'status':task['status'],'steps':len(task['steps']),'events':len(task['events']),'idempotent_create':s.get('idempotent_create',False),'after_restart_status':s.get('afterRestartStatus'),'live_weather':True,'places':len(plan['evidence']['places']),'real_model_plan':True,'pdf':True,'metrics':task.get('metrics')}
(ROOT/'logs/task-v1-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False),flush=True)

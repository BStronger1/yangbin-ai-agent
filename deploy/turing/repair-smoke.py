"""Acceptance for one bounded repair of the prior real task. prepare is read-only.
Never creates another task and never retries the model. Browser starts the one repair.
"""
from pathlib import Path
import hashlib,json,sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'deploy/turing'))
from session_client import SessionClient
PLAN_ID='deb8e2df-b6d2-4dba-baf8-cfcb9f7cf23c'
e=json.loads((ROOT/'secrets.json').read_text())['env'];c=SessionClient('http://'+e['PUBLIC_BIND']+':'+e['PUBLIC_PORT'],e['ACCESS_USER'],e['ACCESS_PASSWORD'])
statefile=ROOT/'logs/repair-v4-state.json'
def digest(data):return hashlib.sha256(data).hexdigest()
if sys.argv[1]=='prepare':
 assert not statefile.exists(),'Existing acceptance state; inspect it instead of starting another run'
 plans=c.call('/api/ai/plans/'+PLAN_ID);assert plans[0]['version']==1
 task=c.call('/api/ai/tasks/'+PLAN_ID);assert task['status']=='SUCCEEDED' and task['planVersion']==1
 repair=c.call('/api/ai/plans/'+PLAN_ID+'/repair');assert repair['status']=='NOT_STARTED'
 original=c.call(task['pdfUrl'])
 s={'planId':PLAN_ID,'sourceVersion':1,'originalPlan':plans[0],'originalTask':task,'originalPdfSha256':digest(original)}
 statefile.write_text(json.dumps(s,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({'ready':True,'planId':PLAN_ID,'sourceVersion':1}),flush=True)
elif sys.argv[1]=='verify':
 s=json.loads(statefile.read_text(encoding='utf-8'));id=s['planId']
 result=c.call('/api/ai/plans/'+id+'/repair');assert result['status'] in {'REPAIRED','REJECTED','FAILED','INTERRUPTED','CONFLICT'}
 assert result['attempts']==1
 # Repeat POST deliberately: it must return the existing terminal operation, not call the model again.
 repeated=c.call('/api/ai/plans/'+id+'/repair','POST',{'expectedVersion':s['sourceVersion']});assert repeated==result
 task=c.call('/api/ai/tasks/'+id);assert task==s['originalTask']
 assert digest(c.call(task['pdfUrl']))==s['originalPdfSha256']
 plans=c.call('/api/ai/plans/'+id);old=next(p for p in plans if p['version']==s['sourceVersion']);assert old==s['originalPlan']
 report={'planId':id,'repair':result,'repeat_post_reused':True,'original_task_unchanged':True,'original_plan_unchanged':True,'original_pdf_unchanged':True}
 if result['status']=='REPAIRED':
  new=next(p for p in plans if p['version']==result['targetVersion']);assert new['version']==2
  assert new['requirements']==old['requirements'] and new['evidence']==old['evidence'] and new['sources']==old['sources']
  for a,b in zip(old['itinerary']['stops'],new['itinerary']['stops'],strict=True):assert all(a[k]==b[k] for k in ('time','estimatedCost','placeId'))
  assert new['quality']==result['afterQuality'] and new['itinerary']==result['afterItinerary']
  before=sum(f['severity']=='warning' for f in result['beforeQuality']['findings']);after=sum(f['severity']=='warning' for f in result['afterQuality']['findings']);assert after<before
  out=c.call('/api/ai/plans/'+id+'/2/pdf','POST',{});pdf=c.call(out['url']);assert pdf.startswith(b'%PDF');(ROOT/'logs/repair-v4-plan.pdf').write_bytes(pdf)
  report.update({'before_warnings':before,'after_warnings':after,'new_version':2,'same_evidence_and_structure':True,'new_pdf':True})
 (ROOT/'logs/repair-v4-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps({k:v for k,v in report.items() if k!='repair'},ensure_ascii=False),flush=True)
 print(json.dumps({'status':result['status'],'attempts':result['attempts'],'message':result['message']},ensure_ascii=False),flush=True)
else:raise SystemExit('Expected prepare or verify')

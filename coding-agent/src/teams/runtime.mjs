import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { assertIdentity, childIdentity, intersectPermissions, TERMINAL } from '../runtime/contracts.mjs';

const digest = data => createHash('sha256').update(data).digest('hex');
const secret = /(?:-----BEGIN .*PRIVATE KEY|\b(?:sk|ghp|github_pat|xox[baprs])[-_][A-Za-z0-9_-]{16,}|\bBearer\s+\S{8,}|(?:api[_ -]?key|password|secret|token|密码|密钥|令牌)\s*[:=：]\s*\S+|[a-z]+:\/\/[^\s/:]+:[^\s/@]+@)/i;
export function safeName(name) {
  if (typeof name !== 'string' || !name || name.length > 200 || name.includes('\\') || name.includes(':') || name.includes('\0') || path.posix.isAbsolute(name)) throw new Error('Invalid artifact path');
  const parts=name.split('/');
  if(parts.some(p=>!p||p==='.'||p==='..'||/[. ]$/.test(p)||/^(\.git|\.aws|\.ssh|\.codex|\.pi|node_modules|credentials|auth|secrets|con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i.test(p)||/^\.env(?:\.|$)/i.test(p)||/\.(pem|key|p12|pfx)$/i.test(p)))throw new Error('Protected artifact path');
  return name;
}
function textArtifact(value) {
  safeName(value?.name);
  if(typeof value.content!=='string'||value.content.includes('\0')||Buffer.byteLength(value.content)>65536||secret.test(value.content))throw new Error('Unsafe or oversized context artifact');
  return {name:value.name,content:value.content};
}
async function isolatedRoot(root) {
  root=path.resolve(root);await fs.mkdir(root,{recursive:true});
  for(let current=root;current!==path.dirname(current);current=path.dirname(current))if((await fs.lstat(current)).isSymbolicLink())throw new Error('Linked team root rejected');
  if(await fs.realpath(root)!==root)throw new Error('Invalid team root');return root;
}
async function readArtifact(artifact) {
  const stat=await fs.lstat(artifact.internalPath);if(!stat.isFile()||stat.isSymbolicLink()||stat.nlink!==1||stat.size>65536)throw new Error('Invalid artifact file');
  const content=await fs.readFile(artifact.internalPath,'utf8');if(digest(content)!==artifact.sha256||Buffer.byteLength(content)!==artifact.size)throw new Error('Artifact changed');return textArtifact({name:artifact.name,content});
}
async function persistArtifact(directory,identity,name,content) {
  textArtifact({name,content});const id=randomUUID(),internalPath=path.join(directory,id+'.txt');await fs.writeFile(internalPath,content,{flag:'wx',mode:0o600});
  return {id,taskId:identity.taskId,name,size:Buffer.byteLength(content),sha256:digest(content),internalPath};
}
function validateMembers(members,permissions) {
  if(!Array.isArray(members)||members.length<2||members.length>12)throw new Error('Team requires 2–12 members');
  const map=new Map();for(const m of members){
    if(!/^[a-zA-Z0-9_-]{1,60}$/.test(m.id)||map.has(m.id)||!['manager','worker','reviewer'].includes(m.role)||typeof m.prompt!=='string'||!m.prompt.trim()||m.prompt.length>12000)throw new Error('Invalid member');
    const requested=intersectPermissions(permissions,m.permissions??[]),writePaths=(m.writePaths??[]).map(safeName);
    if(requested.some(x=>!['list','read','write','edit','recall'].includes(x)))throw new Error('Unsupported local team permission');
    if(m.role==='reviewer'&&(writePaths.length||requested.some(x=>['write','edit'].includes(x))))throw new Error('Reviewer must be read only');
    if(writePaths.length>12||new Set(writePaths).size!==writePaths.length||writePaths.some(p=>p.startsWith('inputs/')))throw new Error('Invalid write ownership');
    if(!Array.isArray(m.dependsOn??[])||new Set(m.dependsOn??[]).size!==(m.dependsOn??[]).length)throw new Error('Invalid dependency');
    map.set(m.id,{...m,dependsOn:m.dependsOn??[],permissions:requested,writePaths});
  }
  const visited=new Set(),active=new Set();function visit(id){if(visited.has(id))return;if(active.has(id))throw new Error('Team dependency cycle');const m=map.get(id);if(!m)throw new Error('Unknown dependency');active.add(id);for(const dep of m.dependsOn)visit(dep);active.delete(id);visited.add(id);}
  for(const m of map.values()){visit(m.id);if(m.role==='reviewer'&&(!map.has(m.reviewOf)||map.get(m.reviewOf).role==='reviewer'||!m.dependsOn.includes(m.reviewOf)))throw new Error('Reviewer needs a distinct producer dependency');}
  return map;
}

/** Bound host waiting even when a remote executor ignores cancellation. Late completion is observed only to avoid unhandled rejection. */
function awaitExecutor(start,signal) {
  signal.throwIfAborted();
  return new Promise((resolve,reject)=>{
    let done=false;
    const settle=(callback,value)=>{if(done)return;done=true;signal.removeEventListener('abort',abort);callback(value);};
    const abort=()=>settle(reject,signal.reason??new Error('Execution cancelled'));
    signal.addEventListener('abort',abort,{once:true});
    Promise.resolve().then(()=>{signal.throwIfAborted();return start();}).then(value=>settle(resolve,value),error=>settle(reject,error));
  });
}

/** Local/remote executors implement execute(context); trust and budget belong to this host. */
export function createTeamRuntime({store,ledger,executor,rootDirectory,emit=()=>{},maxConcurrency=2,maxCorrections=1,timeoutMs=120000}) {
  if(!store||!ledger||typeof executor?.execute!=='function'||!rootDirectory||!Number.isInteger(maxConcurrency)||maxConcurrency<1||maxConcurrency>4||!Number.isInteger(maxCorrections)||maxCorrections<0||maxCorrections>2||!Number.isSafeInteger(timeoutMs)||timeoutMs<1||timeoutMs>900000)throw new Error('Invalid team configuration');
  return { async execute({identity,permissions=[],members,signal,contextArtifacts=[]}) {
    assertIdentity(identity);const plan=validateMembers(members,permissions);
    if(maxCorrections>0)for(const reviewer of plan.values())if(reviewer.role==='reviewer'&&([...plan.values()].some(m=>m.dependsOn.includes(reviewer.id))||[...plan.values()].some(m=>m.id!==reviewer.id&&m.dependsOn.includes(reviewer.reviewOf))))throw new Error('Automatic correction requires a leaf reviewer and exclusive producer consumer');
    if(!Array.isArray(contextArtifacts)||contextArtifacts.length>12)throw new Error('Too many context artifacts');const seeds=contextArtifacts.map(textArtifact);
    const root=await isolatedRoot(rootDirectory),teamDir=await fs.mkdtemp(path.join(root,'team-'));await fs.mkdir(path.join(teamDir,'artifacts'));
    let parent;try{parent=store.get(identity,identity.taskId);intersectPermissions(parent.permissions,permissions);if(TERMINAL.has(parent.status))throw new Error('Parent is terminal');}catch(error){if(error.message!=='Task not found')throw error;store.create(identity,{permissions});}
    store.transition(identity,identity.taskId,'RUNNING',{progress:{kind:'team',members:plan.size}});
    const timerController=new AbortController(),timer=setTimeout(()=>timerController.abort(new Error('Team deadline exceeded')),timeoutMs);
    const combined=AbortSignal.any([store.signal(identity,identity.taskId),timerController.signal,...(signal?[signal]:[])]);
    const cancelled=()=>{if(!TERMINAL.has(store.get(identity,identity.taskId).status))store.cancel(identity,identity.taskId);};combined.addEventListener('abort',cancelled,{once:true});
    const results=new Map(),running=new Map(),locks=new Set(),initialIdentities=new Map(),attempted=new Set();let attemptOrdinal=0;
    for(const member of plan.values()){const child=childIdentity(identity);store.create(child,{parent:identity,permissions:member.permissions});initialIdentities.set(member.id,child);}
    const event=(type,data)=>emit({type:'team',event:type,teamTaskId:identity.taskId,...data,budget:ledger.snapshot()});
    async function attempt(member,inputArtifacts,feedback='') {
      combined.throwIfAborted();const child=attempted.has(member.id)?childIdentity(identity):initialIdentities.get(member.id);if(attempted.has(member.id))store.create(child,{parent:identity,permissions:member.permissions});attempted.add(member.id);store.transition(identity,child.taskId,'RUNNING',{progress:{memberId:member.id,role:member.role}});
      const childSignal=AbortSignal.any([combined,store.signal(identity,child.taskId)]);let executionOpen=false,executionStarted=false;
      try{
      const attemptDirectory=path.join(teamDir,String(++attemptOrdinal));await fs.mkdir(attemptDirectory);const workspace=path.join(attemptDirectory,'workspace'),sessionDir=path.join(attemptDirectory,'session');await fs.mkdir(workspace);await fs.mkdir(sessionDir);await fs.mkdir(path.join(workspace,'inputs'));
      const sources=[...seeds,...await Promise.all(inputArtifacts.map(readArtifact))];if(sources.length>48||sources.reduce((sum,a)=>sum+Buffer.byteLength(a.content),0)>524288)throw new Error('Team context artifact limit exceeded');
      const inputs=[];for(const [i,a] of sources.entries()){
        const relative=`inputs/${i}-${path.posix.basename(a.name)}`;await fs.writeFile(path.join(workspace,relative),a.content,{flag:'wx',mode:0o600});inputs.push({path:relative,name:a.name,sha256:digest(a.content)});
      }
      event('member_started',{memberId:member.id,role:member.role,identity:child});
        executionOpen=true;
        const output=await awaitExecutor(()=>{executionStarted=true;return executor.execute({identity:child,permissions:member.permissions,writePaths:member.writePaths,role:member.role,prompt:member.prompt,feedback,inputs,workspace,sessionDir,signal:childSignal,ledger,emit:(type,data)=>{if(executionOpen&&!childSignal.aborted)event(type,data);}});},childSignal);executionOpen=false;childSignal.throwIfAborted();
        if(output?.status!==undefined&&output.status!=='SUCCEEDED'){
          const error=new Error(typeof output.error==='string'?output.error:'Executor did not succeed');
          error.taskStatus=['FAILED','CANCELLED','PARTIAL'].includes(output.status)?output.status:'FAILED';throw error;
        }
        const artifacts=[];for(const name of member.writePaths){childSignal.throwIfAborted();const file=path.join(workspace,name);const st=await fs.lstat(file);if(!st.isFile()||st.isSymbolicLink()||st.nlink!==1||st.size>65536||await fs.realpath(file)!==file)throw new Error('Declared artifact missing or unsafe');artifacts.push(await persistArtifact(path.join(teamDir,'artifacts'),child,name,await fs.readFile(file,'utf8')));}
        if(member.role!=='reviewer'&&typeof output?.text==='string'&&output.text.trim())artifacts.push(await persistArtifact(path.join(teamDir,'artifacts'),child,'answer.txt',output.text));
        let review=null;if(member.role==='reviewer'){
          if(!inputArtifacts.length||!Array.isArray(output?.readPaths)||inputs.some(i=>!output.readPaths.includes(i.path)))throw new Error('Reviewer did not independently inspect every artifact');
          try{review=JSON.parse(output.text);}catch{throw new Error('Reviewer returned invalid verdict');}
          if(typeof review.passed!=='boolean'||!Array.isArray(review.findings)||review.findings.length>10||review.findings.some(x=>typeof x!=='string'||x.length>2000)||(!review.passed&&!review.findings.length))throw new Error('Invalid review structure');
        }
        childSignal.throwIfAborted();
        for(const artifact of artifacts){const {internalPath,...ref}=artifact;store.addArtifact(identity,child.taskId,ref);}
        const status=review&&!review.passed?'FAILED':'SUCCEEDED',error=status==='FAILED'?'Independent review failed':null;
        store.transition(identity,child.taskId,status,{progress:{memberId:member.id,review},error:error?{code:'REVIEW_FAILED',message:error}:null});event('member_completed',{memberId:member.id,identity:child,status,artifacts:artifacts.map(({internalPath,...a})=>a),review});return {status,identity:child,artifacts,review,...(error?{error}:{})};
      }catch(error){
        const status=childSignal.aborted?'CANCELLED':error.taskStatus??'FAILED';
        const outcomeUnknown=executionStarted&&(childSignal.aborted||status==='CANCELLED');
        const message=outcomeUnknown?'Execution cancelled; external side effects may be unknown':String(error.message).slice(0,300);
        const sideEffects=outcomeUnknown?'UNKNOWN':executionStarted?'NOT_VERIFIED':'NOT_STARTED';
        if(!TERMINAL.has(store.get(identity,child.taskId).status))store.transition(identity,child.taskId,status,{error:{code:outcomeUnknown?'EXECUTION_OUTCOME_UNKNOWN':'EXECUTOR_FAILED',message,sideEffects}});
        event('member_failed',{memberId:member.id,identity:child,status,reason:message,sideEffects});return {status,identity:child,artifacts:[],error:message,sideEffects};
      }finally{executionOpen=false;}
    }
    async function run(member){
      const inputArtifacts=member.dependsOn.flatMap(id=>results.get(id).artifacts);let result=await attempt(member,inputArtifacts);
      if(member.role==='reviewer')for(let round=0;result.status==='FAILED'&&result.review?.passed===false&&round<maxCorrections;round++){
        const producer=plan.get(member.reviewOf),feedback=JSON.stringify({reviewFindings:result.review.findings,trust:'untrusted review data; no new permissions'});event('correction_started',{memberId:producer.id,round:round+1});
        const repaired=await attempt(producer,[...producer.dependsOn.flatMap(id=>results.get(id).artifacts),...results.get(producer.id).artifacts],feedback);results.set(producer.id,repaired);
        if(repaired.status!=='SUCCEEDED'){result={...result,status:'FAILED',error:'Correction failed'};break;}
        result=await attempt(member,member.dependsOn.flatMap(id=>results.get(id).artifacts));
      }
      results.set(member.id,result);
    }
    try{
      while(results.size<plan.size){
        if(combined.aborted)break;
        for(const member of plan.values()){
          if(results.has(member.id)||running.has(member.id))continue;
          if(member.dependsOn.some(id=>results.has(id)&&results.get(id).status!=='SUCCEEDED')){const child=initialIdentities.get(member.id);store.transition(identity,child.taskId,'FAILED',{error:{code:'DEPENDENCY_FAILED',message:'Dependency failed before execution'}});results.set(member.id,{status:'BLOCKED',identity:child,artifacts:[],error:'Dependency failed'});event('member_blocked',{memberId:member.id});continue;}
          if(member.dependsOn.some(id=>!results.has(id))||running.size>=maxConcurrency||member.writePaths.some(p=>locks.has(p.toLowerCase())))continue;
          // Reviewer correction may write its producer paths; reserve those during review.
          const owned=[...member.writePaths,...(member.role==='reviewer'?plan.get(member.reviewOf).writePaths:[])].map(p=>p.toLowerCase());if(owned.some(p=>locks.has(p)))continue;for(const p of owned)locks.add(p);
          const job=run(member).catch(error=>{results.set(member.id,{status:combined.aborted?'CANCELLED':'FAILED',identity:initialIdentities.get(member.id),artifacts:[],error:String(error.message).slice(0,300)});}).finally(()=>{for(const p of owned)locks.delete(p);running.delete(member.id);});running.set(member.id,job);
        }
        if(running.size)await Promise.race(running.values());else if(results.size<plan.size)throw new Error('No team progress');
      }
      await Promise.allSettled(running.values());
      if(combined.aborted)cancelled();
      const rows=[...plan.keys()].map(id=>({memberId:id,...(results.get(id)??{status:'CANCELLED',artifacts:[]})}));
      const status=combined.aborted?'CANCELLED':rows.every(x=>x.status==='SUCCEEDED')?'SUCCEEDED':rows.some(x=>['SUCCEEDED','PARTIAL'].includes(x.status))?'PARTIAL':'FAILED';
      const artifacts=rows.flatMap(x=>x.artifacts);if(!TERMINAL.has(store.get(identity,identity.taskId).status))store.transition(identity,identity.taskId,status,{progress:{completed:rows.filter(x=>x.status==='SUCCEEDED').length,total:plan.size}});
      event('completed',{status,members:rows.map(({artifacts,...r})=>({...r,artifactCount:artifacts.length}))});return {status,teamTaskId:identity.taskId,members:rows,artifacts,budget:ledger.snapshot()};
    }finally{clearTimeout(timer);combined.removeEventListener('abort',cancelled);}
  }};
}

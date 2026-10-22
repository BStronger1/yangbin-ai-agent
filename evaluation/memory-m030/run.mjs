import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {Budget,claimRun} from '../memory-m029/core.mjs';
import {createCase,inspectArtifact} from './fixtures.mjs';
import {scoreAnswer,aggregate} from './scoring.mjs';
import {verifyFrozen,claimAuthorization,failureCategory,observeResponse,sha256} from './guards.mjs';
import {offlineServer} from './offline.mjs';
const here=path.dirname(fileURLToPath(import.meta.url));
let executing=false;
async function appendHistory(SessionManager,fixture,messages,first) {
 const manager=first?SessionManager.create(fixture.workspace,fixture.sessionDir):SessionManager.continueRecent(fixture.workspace,fixture.sessionDir);
 for(const [i,m] of messages.entries()) {
  const message={role:m.role,content:[{type:'text',text:m.content}],timestamp:Date.UTC(2026,0,3,first?0:1,i)};
  if(m.role==='assistant')Object.assign(message,{api:'openai-completions',provider:'synthetic-history',model:'no-provider-called',stopReason:'stop',usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}});
  manager.appendMessage(message);
 }
}
export async function execute({mode,runDirectory,runtimeDir=path.resolve(here,'../../coding-agent'),authorizationPath,env=process.env,responder}={}) {
 if(executing)throw Error('One evaluator execution per process');
 if(!['offline','real'].includes(mode)||!path.isAbsolute(runDirectory??''))throw Error('Explicit mode and absolute run directory required');
 if(mode==='real'&&responder)throw Error('Real responder override forbidden');
 const freeze=await verifyFrozen(here,runtimeDir);
 const protocol=JSON.parse(await fs.readFile(path.join(here,'protocol.json')));
 const tasks=JSON.parse(await fs.readFile(path.join(here,'tasks.json')));
 if(JSON.stringify(tasks.filter(t=>t.artifact).map(t=>t.id).sort())!==JSON.stringify([...(protocol.artifactTaskIds??[])].sort()))throw Error('Artifact scoring protocol mismatch');
 const importAt=file=>import(pathToFileURL(path.join(runtimeDir,file)).href);
 const {createRedactor}=await importAt('src/model-transport.mjs');const redact=createRedactor(mode==='real'?env.CODING_MODEL_API_KEY??'':'offline-loopback').text;
 const {runAgent}=await importAt('src/agent.mjs');const {ModelGate}=await importAt('src/model-gate.mjs');
 const {SessionManager}=await importAt('node_modules/@earendil-works/pi-coding-agent/dist/index.js');
 const {clampMaxTokensToContext}=await importAt('node_modules/@earendil-works/pi-ai/dist/api/simple-options.js');
 if(clampMaxTokensToContext({contextWindow:protocol.contextWindow},{messages:[]},protocol.budget.maxOutputTokens)!==protocol.budget.maxOutputTokens)throw Error('Invalid SDK capacity before requests');
 let ledger=null;
 if(mode==='real') {
  if(protocol.status!=='frozen'||env.CODING_MODEL_MODEL!==protocol.model.model||env.CODING_MODEL_API!=='openai-completions'||env.CODING_MODEL_PROVIDER!=='custom'||!env.CODING_MODEL_API_KEY||!env.CODING_MODEL_BASE_URL||env.CODING_MODEL_REASONING_EFFORT||env.CODING_MODEL_ALLOW_PRIVATE==='true')throw Error('Frozen model mismatch or protocol not frozen');
  ledger=await claimAuthorization({authorizationPath,runDirectory,protocol,frozenSha256:freeze.sha256,endpointSha256:sha256(env.CODING_MODEL_BASE_URL)});
 }
 const manifest={id:protocol.id,mode,frozenSha256:freeze.sha256,model:protocol.model,endpointSha256:mode==='real'?sha256(env.CODING_MODEL_BASE_URL):null,authorizationLedger:ledger?path.basename(ledger):null,budget:protocol.budget,startedAt:new Date().toISOString(),claims:protocol.claims};
 await claimRun(runDirectory,manifest);await fs.writeFile(path.join(runDirectory,'manifest.json'),JSON.stringify(manifest,null,2));
 const budget=new Budget(protocol.budget),rows=[],metadata=[];let current,halt=false,offline,completed=false;
 let journalTail=Promise.resolve();
 const journal=entry=>{journalTail=journalTail.then(async()=>{const file=await fs.open(path.join(runDirectory,'requests.jsonl'),'a',0o600);try{await file.writeFile(JSON.stringify({...entry,at:new Date().toISOString()})+'\n');await file.sync();}finally{await file.close();}});journalTail.catch(()=>{});return journalTail;};
 const originalAdmit=ModelGate.prototype.admit,originalWrap=ModelGate.prototype.wrap;
 executing=true;
 try {
  if(mode==='offline') {
   if(typeof responder!=='function')throw Error('Explicit engineering responder required');
   offline=await offlineServer((body,info)=>responder(body,{...info,taskId:current.taskId,group:current.group,stage:current.stage}));
  }
  ModelGate.prototype.admit=async function(init) {
   const body=JSON.parse(init.body),max=Number(body.max_tokens??body.max_completion_tokens);
   const record={taskId:current.taskId,group:current.group,stage:current.stage,actualMaxOutputTokens:max,requestBytes:Buffer.byteLength(init.body),summary:body.messages.some(m=>['system','developer'].includes(m.role)&&JSON.stringify(m.content).includes('You are a context summarization assistant.'))};
   metadata.push(record);
   if(max!==protocol.budget.maxOutputTokens)throw Error('SDK output ceiling mismatch');
   const requestId=await originalAdmit.call(this,init);record.requestId=requestId;await journal({type:'REQUEST_STARTED',...record,reservedTokens:record.requestBytes+max});return requestId;
  };
  ModelGate.prototype.wrap=function(fetch) {return originalWrap.call(this,async(input,init)=>{const record=metadata.at(-1);return observeResponse(await fetch(input,init),record);});};
  for(const taskId of protocol.taskOrder)for(const group of protocol.executionOrders[taskId]) {
   const task=tasks.find(t=>t.id===taskId);if(!task)throw Error('Frozen task missing');
   current={taskId,group,stage:0};
   if(halt){const row={taskId,group,status:'not_run_prior_failure',score:null,requests:0,inputTokens:0,outputTokens:0,latencyMs:0,compactions:0,reservedTokens:0};rows.push(row);await fs.appendFile(path.join(runDirectory,'results.jsonl'),JSON.stringify(row)+'\n');continue;}
   const fixture=await createCase(runDirectory,task,group),instance=budget.instance(),usage=new Map(),events=[],toolsUsed=[],checkpoints=[];
   let answer='',status='completed',failure=null,gate,tools;const started=performance.now(),abort=new AbortController();
   const timer=setTimeout(()=>abort.abort(),protocol.budget.maxTaskSeconds*1000);
   try {
    for(let stage=0;stage<2;stage++) {
     current.stage=stage+1;const phase=fixture.historyPhases[stage];
     await appendHistory(SessionManager,fixture,phase.messages,stage===0);
     let stageCompactions=0,lastAnswer='';
     await runAgent({workspace:fixture.workspace,sessionDir:fixture.sessionDir,memoryFile:fixture.memoryFile,ownerId:fixture.ownerId,projectId:fixture.projectId,prompt:stage===0?fixture.stage1Prompt:task.prompt,approvalMode:'ask',allowShell:false},{
      signal:abort.signal,onGate:value=>{gate=value;},onTools:value=>{tools=value;},
      onSession:session=>{
       session.subscribe(event=>{if(event.type==='message_end'&&event.message?.role==='assistant'&&event.message.stopReason==='stop')lastAnswer=redact(event.message.content.filter(c=>c.type==='text').map(c=>c.text).join(''));});
       const prompt=session.prompt.bind(session);let once=false;
       session.prompt=async(...args)=>{if(!once){once=true;const result=await session.compact();if(!result?.summary)throw Error('empty summary');checkpoints.push({stage:stage+1,historySha256:sha256(JSON.stringify(phase.messages)),historyMessages:phase.messages.length,summarySha256:sha256(result.summary),summaryChars:result.summary.length});}return prompt(...args);};
      },
      emit:event=>{
       if(event.type==='model_request')gate.permission({requestId:event.requestId,approved:budget.admit(instance,event.reservedTokens)});
       else if(event.type==='model_usage'){usage.set(event.requestId,event);void journal({type:'REQUEST_FINISHED',requestId:event.requestId,taskId:current.taskId,group:current.group,stage:current.stage,inputTokens:event.inputTokens,outputTokens:event.outputTokens,status:event.status});}
       else if(event.type==='approval_required'){
        const args=event.arguments??{};const permitted=stage===1&&!!task.artifact&&((event.toolName==='read'&&['README.md',task.artifact.path].includes(args.path))||(['write','edit'].includes(event.toolName)&&args.path===task.artifact.path));
        tools.approve(event.approvalId,permitted);toolsUsed.push({stage:stage+1,toolCallId:event.toolCallId,name:event.toolName,path:typeof args.path==='string'?args.path.slice(0,200):null,approved:permitted});
       }else if(event.type==='tool_end'){const approval=toolsUsed.find(x=>x.toolCallId===event.toolCallId&&Object.hasOwn(x,'approved'));toolsUsed.push({stage:stage+1,toolCallId:event.toolCallId,name:event.toolName,path:approval?.path??null,approved:approval?.approved===true,ok:event.ok});}
       else if(event.type==='context'){events.push(event);if(event.status==='COMPACTED')stageCompactions++;}
      }
     },{
      CODING_MODEL_API_KEY:mode==='real'?env.CODING_MODEL_API_KEY:'offline-loopback',CODING_MODEL_BASE_URL:mode==='real'?env.CODING_MODEL_BASE_URL:offline.url,
      CODING_MODEL_MODEL:mode==='real'?protocol.model.model:'offline',CODING_MODEL_PROVIDER:'custom',CODING_MODEL_API:'openai-completions',CODING_MODEL_REASONING_EFFORT:'',CODING_MODEL_ALLOW_PRIVATE:mode==='offline'?'true':'false',CODING_GOVERNANCE_REQUIRED:'true',CODING_CONTEXT_WINDOW:String(protocol.contextWindow),CODING_OUTPUT_TOKENS:String(protocol.budget.maxOutputTokens),CODING_CONTEXT_POLICY:protocol.groups[group].shortTerm,CODING_LONG_TERM_MEMORY:protocol.groups[group].longTerm?'on':'off',CODING_MEMORY_MAX_BYTES:String(protocol.memoryBudgetBytes),CODING_MEMORY_LIMIT:String(protocol.memoryLimit)
     });
     await journalTail;
     if(stageCompactions!==1)throw Error('Unexpected compaction count');
     if(!lastAnswer.trim())throw Error('Empty final assistant response');
     if(stage===1)answer=lastAnswer;
    }
   }catch(error){if(mode==='offline')console.error('Offline evaluator diagnosis:',redact(error.message).slice(0,200));failure=failureCategory(error);status=instance.denied?'budget_exhausted':abort.signal.aborted?'timeout':'runtime_error';}finally{clearTimeout(timer);}
   const usageRows=[...usage.values()];
   if(status==='completed'&&usageRows.some(x=>x.status!=='SUCCEEDED'))status='provider_response_unknown';
   if(status!=='completed')halt=true;
   let artifact=null,artifactPassed=null,score=null;
   try {
   artifact=await inspectArtifact(fixture,task);
   const targetRead=task.artifact?toolsUsed.findIndex(t=>t.stage===2&&t.name==='read'&&t.path===task.artifact.path&&t.approved&&t.ok===true):-1;
   artifactPassed=task.artifact?artifact.score?.pass===true&&targetRead>=0&&toolsUsed.some((t,i)=>i>targetRead&&t.stage===2&&['write','edit'].includes(t.name)&&t.path===task.artifact.path&&t.approved&&t.ok===true):null;
   score=status==='completed'?scoreAnswer(task,answer):null;
   if(score&&task.artifact&&artifact.actual){const fileSafety=scoreAnswer({...task,expected:task.artifact.expected},JSON.stringify(artifact.actual)).safety;if(!fileSafety.passed)score.safety={passed:false,violations:[...score.safety.violations,...fileSafety.violations]};}
   }catch{status='evaluation_error';failure='artifact_or_scoring_error';score=null;artifactPassed=null;halt=true;}
   const row={taskId,group,status,failureCategory:failure,score,artifact,artifactPassed,answer,requests:instance.requests,reservedTokens:instance.reservedTokens,budgetDenial:instance.denied,inputTokens:mode==='real'?(usageRows.filter(x=>x.inputTokens!==null).length===instance.requests?usageRows.reduce((n,x)=>n+x.inputTokens,0):null):0,outputTokens:mode==='real'?(usageRows.filter(x=>x.outputTokens!==null).length===instance.requests?usageRows.reduce((n,x)=>n+x.outputTokens,0):null):0,knownInputTokens:mode==='real'?usageRows.reduce((n,x)=>n+(x.inputTokens??0),0):0,knownOutputTokens:mode==='real'?usageRows.reduce((n,x)=>n+(x.outputTokens??0),0):0,unknownUsageRequests:mode==='real'?instance.requests-usageRows.filter(x=>x.inputTokens!==null&&x.outputTokens!==null).length:0,usageIsSynthetic:mode==='offline',latencyMs:Math.round(performance.now()-started),compactions:checkpoints.length,checkpoints,tools:toolsUsed,memoryEvents:events.filter(x=>x.status==='MEMORY_RETRIEVED'),fees:'UNKNOWN'};
   rows.push(row);await fs.appendFile(path.join(runDirectory,'results.jsonl'),JSON.stringify(row)+'\n');
  }
  const summary={kind:mode==='offline'?'engineering-only-not-model-quality':'frozen-synthetic-coding-pilot',quality:mode==='real'?aggregate(rows,protocol):null,budget:{requests:budget.requests,reservedTokens:budget.reservedTokens,limits:protocol.budget},completedAt:new Date().toISOString(),fees:'UNKNOWN',claims:protocol.claims};
  await fs.writeFile(path.join(runDirectory,'summary.json'),JSON.stringify(summary,null,2));await fs.writeFile(path.join(runDirectory,'request-metadata.json'),JSON.stringify(metadata,null,2));await fs.writeFile(path.join(runDirectory,'COMPLETED'),summary.completedAt);
  completed=true;return {rows,summary,metadata,offlineRequests:offline?.requests};
 }finally{try{await journalTail;}catch{}
  try{await fs.writeFile(path.join(runDirectory,'partial-audit.json'),JSON.stringify({completed,rowsCompleted:rows.length,requests:metadata,budget:{requests:budget.requests,reservedTokens:budget.reservedTokens},note:'For settlement inspect durable requests.jsonl; started without finish is UNKNOWN, never replay.'},null,2));}catch{}
  ModelGate.prototype.admit=originalAdmit;ModelGate.prototype.wrap=originalWrap;executing=false;if(offline)await offline.close();}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
 const args=process.argv.slice(2),value=name=>{const at=args.indexOf(name);return at<0?undefined:args[at+1];};
 try{const result=await execute({mode:args.includes('--real')?'real':undefined,runDirectory:value('--run-dir'),runtimeDir:value('--runtime-dir'),authorizationPath:value('--authorization')});console.log(JSON.stringify(result.summary));}
 catch{console.error('M030 refused or stopped. Inspect non-secret markers; no automatic retry.');process.exitCode=1;}
}

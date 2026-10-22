import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { createTeamRuntime, safeName } from '../src/teams/runtime.mjs';
import { createLocalExecutor } from '../src/teams/local-executor.mjs';
import { TaskStore, BudgetLedger } from '../src/runtime/contracts.mjs';
import { ToolRuntime } from '../src/tools.mjs';
import { ModelGate } from '../src/model-gate.mjs';

const identity={accountId:'a',projectId:'p',sessionId:'s',taskId:'root',agentId:'manager'};
const permissions=['list','read','write','edit'];
const plan=()=>[
 {id:'analysis',role:'manager',prompt:'Analyze only',dependsOn:[],permissions:['read'],writePaths:[]},
 {id:'implementation',role:'worker',prompt:'Produce GOOD',dependsOn:['analysis'],permissions:['read','write'],writePaths:['result.txt']},
 {id:'review',role:'reviewer',prompt:'Check actual result',dependsOn:['implementation'],permissions:['read'],writePaths:[],reviewOf:'implementation'},
];
async function fixture(t,{maxRequests=50,behavior={},maxCorrections=1,maxConcurrency=2}={}){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-teams-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));
 let networkCalls=0,active=0,peak=0;const contexts=[],events=[];
 const server=http.createServer((req,res)=>{networkCalls++;req.resume();req.on('end',()=>{res.writeHead(200,{'content-type':'text/event-stream'});res.end(behavior.noUsage?'data: [DONE]\n\n':'data: {"usage":{"prompt_tokens":20,"completion_tokens":10}}\n\ndata: [DONE]\n\n');});});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
 const url=`http://127.0.0.1:${server.address().port}/chat`;
 const runAgent=async(input,hooks)=>{
   const {emit,signal,onGate,onTools,executionContext}=hooks;contexts.push({workspace:input.workspace,sessionDir:input.sessionDir,identity:executionContext.identity});
   active++;peak=Math.max(peak,active);
   const tools=new ToolRuntime({...input,emit,signal,approvalTimeoutMs:1000});await tools.init();onTools(tools);
   const gate=new ModelGate({emit,signal,required:true,timeoutMs:1000});onGate(gate);
   try{
     await (await gate.wrap(fetch)(url,{method:'POST',body:JSON.stringify({max_tokens:128,messages:[{role:'user',content:input.prompt}]})})).text();
     if(input.prompt.startsWith('FAIL'))throw new Error('Synthetic executor failure');
     if(behavior.delay)await new Promise((resolve,reject)=>{const timer=setTimeout(resolve,behavior.delay);signal.addEventListener('abort',()=>{clearTimeout(timer);reject(signal.reason);},{once:true});});
     const reviewer=input.prompt.includes('Act as an independent reviewer'),paths=[...input.prompt.matchAll(/"path":"([^"]+)"/g)].map(x=>x[1]);let bad=false;
     if(!(reviewer&&behavior.skipRead))for(const p of paths){const result=await tools.execute('read',{path:p});assert.equal(result.isError,undefined);if(result.content?.some(x=>x.text?.includes('BAD')))bad=true;}
     let text='Analysis complete';
     if(input.prompt.startsWith('Produce')){
       const content=behavior.alwaysBad?'BAD':behavior.correctOnFeedback&&!input.prompt.includes('Untrusted review feedback:')?'BAD':'GOOD';
       const result=await tools.execute('write',{path:'result.txt',content});if(result.isError)throw new Error('Write rejected');text='Created result artifact';
     }
     if(input.prompt.startsWith('Unauthorized'))await assert.rejects(()=>tools.execute('write',{path:'outside.txt',content:'bad'}),/ownership|permission/);
     if(reviewer)text=JSON.stringify({passed:!bad,findings:bad?['result.txt must contain GOOD']:[]});
     emit({type:'text_delta',delta:text});
   }finally{active--;tools.cancel();gate.close();}
 };
 const store=new TaskStore({emit:e=>events.push(e)}),ledger=new BudgetLedger({maxRequests,maxTokens:200000});
 const parentGates=new Map(),parentTools=new Map(),parentReservations=new Map();
 const executor=createLocalExecutor(behavior.parentBridge?{runAgent,approve:null,budgetManagedByRunAgent:true,onGate:(g,id)=>parentGates.set(id.taskId,g),onTools:(t,id)=>parentTools.set(id.taskId,t),onEvent:(value,id)=>{
  if(value.type==='model_request'){const reservationId=ledger.reserve({identity:id,requestId:value.requestId,reservedTokens:value.reservedTokens});parentReservations.set(value.requestId,reservationId);parentGates.get(id.taskId).permission({requestId:value.requestId,approved:true});}
  if(value.type==='model_usage')ledger.settle({reservationId:parentReservations.get(value.requestId),status:value.status,usage:{inputTokens:value.inputTokens,outputTokens:value.outputTokens}});
  if(value.type==='approval_required')parentTools.get(id.taskId).approve(value.approvalId,true);
  events.push({bridgeEvent:value.type,taskId:id.taskId});
 }}:{runAgent,approve:async()=>true});
 const runtime=createTeamRuntime({store,ledger,executor,rootDirectory:root,emit:e=>events.push(e),maxCorrections,maxConcurrency,timeoutMs:5000});
 return {runtime,store,ledger,root,contexts,events,networkCalls:()=>networkCalls,peak:()=>peak};
}

test('real isolated local executors run dependency chain and independent artifact review',async t=>{
 const f=await fixture(t);const result=await f.runtime.execute({identity,permissions,members:plan(),contextArtifacts:[{name:'requirements.txt',content:'Must contain GOOD'}]});
 assert.equal(result.status,'SUCCEEDED');assert.equal(f.contexts.length,3);assert.equal(new Set(f.contexts.map(x=>x.workspace)).size,3);assert.equal(new Set(f.contexts.map(x=>x.sessionDir)).size,3);assert.equal(new Set(f.contexts.map(x=>x.identity.taskId)).size,3);assert.equal(f.networkCalls(),3);assert.equal(result.budget.requests,3);assert.equal(result.budget.tokens,90);
 const artifact=result.artifacts.find(a=>a.name==='result.txt');assert.equal(await fs.readFile(artifact.internalPath,'utf8'),'GOOD');assert.match(artifact.sha256,/^[0-9a-f]{64}$/);assert.ok(f.events.some(e=>e.event==='member_completed'&&e.review?.passed));
});
test('fan-out is genuinely concurrent with bounded executor count',async t=>{
 const f=await fixture(t,{behavior:{delay:30},maxConcurrency:2});const members=[...Array(3)].map((_,i)=>({id:'worker'+i,role:'worker',prompt:'Analyze '+i,permissions:['read'],dependsOn:[],writePaths:[]}));
 const result=await f.runtime.execute({identity,permissions,members});assert.equal(result.status,'SUCCEEDED');assert.equal(f.peak(),2);assert.equal(f.networkCalls(),3);
});
test('write ownership is serialized even though physical workspaces are separate',async t=>{
 const f=await fixture(t,{behavior:{delay:20},maxConcurrency:2});const members=['one','two'].map(id=>({id,role:'worker',prompt:'Produce GOOD',permissions:['write'],writePaths:['result.txt'],dependsOn:[]}));
 const result=await f.runtime.execute({identity,permissions,members});assert.equal(result.status,'SUCCEEDED');assert.equal(f.peak(),1);
});
test('review failure produces one actual correction and a fresh independent review context',async t=>{
 const f=await fixture(t,{behavior:{correctOnFeedback:true}});const result=await f.runtime.execute({identity,permissions,members:plan()});assert.equal(result.status,'SUCCEEDED');assert.equal(f.contexts.length,5);assert.equal(new Set(f.contexts.map(x=>x.sessionDir)).size,5);assert.equal(f.networkCalls(),5);assert.equal(f.events.filter(e=>e.event==='correction_started').length,1);
});
test('bounded failed review remains partial, never self-certified success',async t=>{
 const f=await fixture(t,{behavior:{alwaysBad:true}});const result=await f.runtime.execute({identity,permissions,members:plan()});assert.equal(result.status,'PARTIAL');assert.equal(result.members.find(m=>m.memberId==='review').status,'FAILED');assert.equal(f.networkCalls(),5);
});
test('reviewer without actual read evidence cannot pass',async t=>{
 const f=await fixture(t,{behavior:{skipRead:true}});const result=await f.runtime.execute({identity,permissions,members:plan()});assert.equal(result.status,'PARTIAL');assert.match(result.members.find(m=>m.memberId==='review').error,/inspect/);
});
test('global model budget stops descendants before network and reports partial',async t=>{
 const f=await fixture(t,{maxRequests:1});const result=await f.runtime.execute({identity,permissions,members:plan()});assert.equal(result.status,'PARTIAL');assert.equal(f.networkCalls(),1);assert.equal(result.budget.requests,1);assert.equal(result.members.find(m=>m.memberId==='review').status,'BLOCKED');
});
test('parent cancellation propagates to running contexts and prevents dependents',async t=>{
 const f=await fixture(t,{behavior:{delay:200}}),controller=new AbortController();const pending=f.runtime.execute({identity,permissions,members:plan(),signal:controller.signal});setTimeout(()=>controller.abort(new Error('test cancellation')),60);const result=await pending;assert.equal(result.status,'CANCELLED');assert.ok(f.networkCalls()<=1);assert.ok(f.store.list(identity).every(x=>['CANCELLED'].includes(x.status)));
});
test('invalid DAG, role escalation and secret context reject before requests',async t=>{
 const f=await fixture(t);let members=plan();members[0].dependsOn=['review'];await assert.rejects(()=>f.runtime.execute({identity,permissions,members}),/cycle/);
 members=plan();members[2].permissions=['write'];await assert.rejects(()=>f.runtime.execute({identity,permissions,members}),/read only/);
 members=plan();members[1].permissions=['bash'];await assert.rejects(()=>f.runtime.execute({identity,permissions,members}),/permissions/);
 await assert.rejects(()=>f.runtime.execute({identity,permissions,members:plan(),contextArtifacts:[{name:'data.txt',content:'password=synthetic-secret'}]}),/Unsafe/);assert.equal(f.networkCalls(),0);
 for(const name of ['../escape','.env','secrets.txt','nested/../x','C:/x','bad\\x'])assert.throws(()=>safeName(name));
});
test('member tool cannot write undeclared path despite existing write permission',async t=>{
 const f=await fixture(t);const result=await f.runtime.execute({identity,permissions,members:[{id:'a',role:'worker',prompt:'Unauthorized write',permissions:['write'],writePaths:[],dependsOn:[]},{id:'b',role:'worker',prompt:'Analyze',permissions:['read'],writePaths:[],dependsOn:['a']}]});assert.equal(result.status,'SUCCEEDED');for(const c of f.contexts)await assert.rejects(()=>fs.stat(path.join(c.workspace,'outside.txt')));
});

test('unknown provider usage keeps reservations rather than refunding child requests',async t=>{
 const f=await fixture(t,{behavior:{noUsage:true}});const result=await f.runtime.execute({identity,permissions,members:plan()});assert.equal(result.status,'SUCCEEDED');assert.equal(result.budget.requests,3);assert.ok(result.budget.tokens>90);assert.ok([...f.ledger.entries.values()].every(x=>x.usage===null));
});
test('correction topology rejects consumers that could use pre-review artifacts',async t=>{
 const f=await fixture(t);const members=plan();members.push({id:'early-consumer',role:'worker',prompt:'Analyze',permissions:['read'],writePaths:[],dependsOn:['implementation']});await assert.rejects(()=>f.runtime.execute({identity,permissions,members}),/exclusive producer/);assert.equal(f.networkCalls(),0);
});

test('trusted parent IPC receives raw budget and approval events without double admission or automatic rejection',async t=>{
 const f=await fixture(t,{behavior:{parentBridge:true}});const result=await f.runtime.execute({identity,permissions,members:plan()});assert.equal(result.status,'SUCCEEDED');assert.equal(result.budget.requests,3);assert.equal(result.budget.tokens,90);assert.equal(f.networkCalls(),3);
 for(const kind of ['model_request','model_usage','approval_required','approval_resolved'])assert.ok(f.events.some(x=>x.bridgeEvent===kind),kind);
});

async function isolatedRuntimeFixture(t,executor,options={}) {
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-teams-outcomes-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));
 const events=[],store=new TaskStore({emit:e=>events.push(e)}),ledger=new BudgetLedger({maxRequests:10,maxTokens:10000});
 const runtime=createTeamRuntime({store,ledger,executor,rootDirectory:root,emit:e=>events.push(e),maxCorrections:0,timeoutMs:1000,...options});
 const members=[{id:'one',role:'worker',prompt:'Execute',permissions:[],writePaths:[],dependsOn:[]},{id:'two',role:'worker',prompt:'Dependent',permissions:[],writePaths:[],dependsOn:['one']}];
 return {runtime,store,ledger,members,events};
}
test('explicit remote FAILED CANCELLED and PARTIAL outcomes never become success',async t=>{
 for(const status of ['FAILED','CANCELLED','PARTIAL']){
  const f=await isolatedRuntimeFixture(t,{execute:async()=>({status,error:'remote rejected',artifacts:[]})});const result=await f.runtime.execute({identity,permissions:[],members:f.members});
  const row=result.members.find(x=>x.memberId==='one');assert.equal(row.status,status);assert.equal(f.store.get(identity,row.identity.taskId).status,status);assert.equal(row.artifacts.length,0);assert.notEqual(result.status,'SUCCEEDED');assert.equal(result.members[1].status,'BLOCKED');
  if(status==='PARTIAL')assert.equal(result.status,'PARTIAL');
 }
});
test('negative independent review with zero corrections agrees with TaskStore terminal state',async t=>{
 const f=await fixture(t,{behavior:{alwaysBad:true},maxCorrections:0});const result=await f.runtime.execute({identity,permissions,members:plan()});const review=result.members.find(x=>x.memberId==='review');assert.equal(result.status,'PARTIAL');assert.equal(review.status,'FAILED');assert.equal(f.store.get(identity,review.identity.taskId).status,'FAILED');assert.equal(f.networkCalls(),3);
});
test('deadline releases host waiting when executor never resolves and ignores cancellation',async t=>{
 let started=0;const f=await isolatedRuntimeFixture(t,{execute:()=>{started++;return new Promise(()=>{});}},{timeoutMs:1000});
 let watchdog;const result=await Promise.race([f.runtime.execute({identity,permissions:[],members:f.members}),new Promise((_,reject)=>{watchdog=setTimeout(()=>reject(new Error('runtime still pending after 5000ms')),5000);})]).finally(()=>clearTimeout(watchdog));
 assert.equal(started,1);assert.equal(result.status,'CANCELLED');assert.equal(result.members[0].sideEffects,'UNKNOWN');assert.equal(result.artifacts.length,0);assert.ok(f.store.list(identity).every(x=>x.status==='CANCELLED'));
});
test('late executor resolution cannot publish success events or artifacts after timeout',async t=>{
 let release,lateEmit;const f=await isolatedRuntimeFixture(t,{execute:context=>{lateEmit=context.emit;return new Promise(resolve=>{release=resolve;});}},{timeoutMs:1000});
 const result=await f.runtime.execute({identity,permissions:[],members:f.members});assert.equal(result.status,'CANCELLED');const eventCount=f.events.length;
 lateEmit('late_external_success',{message:'untrusted completed'});release({status:'SUCCEEDED',text:'late artifact'});await new Promise(resolve=>setTimeout(resolve,30));
 assert.equal(f.events.length,eventCount);assert.equal(result.artifacts.length,0);assert.ok(f.store.list(identity).every(x=>x.status==='CANCELLED'&&x.artifacts.length===0));
});

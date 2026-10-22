import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import express from 'express';
import {once} from 'node:events';
import {createHash,randomUUID} from 'node:crypto';
import {AgentCard,TaskState} from '@a2a-js/sdk';
import {DefaultRequestHandler,InMemoryTaskStore} from '@a2a-js/sdk/server';
import {jsonRpcHandler,agentCardHandler,UserBuilder} from '@a2a-js/sdk/server/express';
import {BudgetLedger,childIdentity} from '../src/runtime/contracts.mjs';
import {createRemoteAgent,cardDigest} from '../src/a2a/client.mjs';
import {createTeamExecutorRouter} from '../src/a2a/team-executor.mjs';
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const parent={accountId:'owner',projectId:'project',sessionId:'session',taskId:'parent',agentId:'parent-agent'};
async function fixture(t){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-a2a-team-'));
 t.after(async()=>{if(path.dirname(root)===path.resolve(os.tmpdir())&&path.basename(root).startsWith('yangbin-a2a-team-'))await fs.rm(root,{recursive:true,force:true});});
 const workspace=path.join(root,'workspace'),sessionDir=path.join(root,'session'),artifactRoot=path.join(sessionDir,'staging');
 await Promise.all([fs.mkdir(path.join(workspace,'inputs'),{recursive:true}),fs.mkdir(artifactRoot,{recursive:true})]);
 const input='controlled source only';await fs.writeFile(path.join(workspace,'inputs','0-source.txt'),input);
 const ledger=new BudgetLedger({maxRequests:10,maxTokens:100000});
 const request={identity:childIdentity(parent),executorId:'registered',role:'worker',permissions:['read','write'],writePaths:['result.md'],prompt:'Produce a result',feedback:'',inputs:[{path:'inputs/0-source.txt',name:'source.txt',sha256:sha(input)}],workspace,sessionDir,signal:new AbortController().signal,ledger};
 return{root,artifactRoot,request,ledger};
}
async function ref(t,{state=TaskState.TASK_STATE_COMPLETED,text='verified remote artifact',onStart}={}){
 const app=express(),server=http.createServer(app);server.listen(0,'127.0.0.1');await once(server,'listening');
 t.after(()=>new Promise(resolve=>{server.closeAllConnections();server.close(resolve);}));
 const base='http://127.0.0.1:'+server.address().port;let calls=0,received,cancelled=0;
 const card=AgentCard.fromJSON({name:'reference',description:'official SDK local reference',version:'1.0.0',supportedInterfaces:[{url:base+'/rpc',protocolBinding:'JSONRPC',protocolVersion:'1.0'}],capabilities:{streaming:true},defaultInputModes:['text/plain'],defaultOutputModes:['text/plain'],skills:[]});
 const executor={async execute(ctx,bus){calls++;received=ctx.userMessage.parts[0].content.value;bus.publish({kind:'task',data:{id:ctx.taskId,contextId:ctx.contextId,status:{state:TaskState.TASK_STATE_WORKING},artifacts:[],history:[]}});onStart?.();
 if(onStart)await new Promise(r=>setTimeout(r,100));
 bus.publish({kind:'artifactUpdate',data:{taskId:ctx.taskId,contextId:ctx.contextId,artifact:{artifactId:'output',name:'result.md',parts:[{content:{$case:'text',value:text},mediaType:'text/plain'}]},append:false,lastChunk:true}});
 bus.publish({kind:'statusUpdate',data:{taskId:ctx.taskId,contextId:ctx.contextId,status:{state}}});bus.finished();},async cancelTask(task,bus){cancelled++;bus.publish({kind:'statusUpdate',data:{taskId:task.id,contextId:task.contextId,status:{state:TaskState.TASK_STATE_CANCELED}}});bus.finished();}};
 const handler=new DefaultRequestHandler(card,new InMemoryTaskStore(),executor);
 app.use((req,res,next)=>req.headers.authorization==='Bearer fixture-credential'?next():res.status(401).end());
 app.use('/.well-known/agent-card.json',agentCardHandler({agentCardProvider:handler}));app.use('/rpc',jsonRpcHandler({requestHandler:handler,userBuilder:UserBuilder.noAuthentication}));
 return{base,card,get calls(){return calls;},get received(){return received;},get cancelled(){return cancelled;}};
}
function registry(f,service,{credentialOwner,permissions=['read'],mutateRef=x=>x}={}){
 return async request=>{
  assert.equal(request.executorId,'registered');assert.equal(request.ledger,f.ledger);
  const agent=createRemoteAgent({identity:request.identity,remoteId:'registered',url:service.base+'/rpc',cardUrl:service.base+'/.well-known/agent-card.json',expectedCardHash:cardDigest(service.card),testLoopback:true,timeoutMs:5000,reservedTokens:5000,budget:request.ledger,remotePermissions:permissions,
   credentialProvider:async()=>({...request.identity,accountId:credentialOwner??request.identity.accountId,remoteId:'registered',endpoint:service.base+'/rpc',expiresAt:Date.now()+60000,accessToken:'fixture-credential',permissions}),authorize:async()=>({approved:true}),
   storeArtifact:async({identity,artifact})=>{const bytes=Buffer.from(artifact.parts.map(p=>p.content.value).join('\n'));const internalPath=path.join(f.artifactRoot,randomUUID()+'.txt');await fs.writeFile(internalPath,bytes,{flag:'wx'});return mutateRef({id:randomUUID(),taskId:identity.taskId,name:artifact.name,size:bytes.length,sha256:sha(bytes),internalPath});}});
  return Object.assign(agent,{binding:{identity:{...request.identity},ledger:request.ledger,artifactRoot:f.artifactRoot}});
 };
}
const router=resolveRemote=>createTeamExecutorRouter({local:{execute:async req=>({local:true,request:req})},resolveRemote});
test('router + real RemoteAgent + official SDK reference server: verified context and materialized output, shared unknown budget',async t=>{
 const f=await fixture(t),service=await ref(t);const result=await router(registry(f,service)).execute(f.request);
 assert.equal(result.status,'SUCCEEDED',JSON.stringify(result));assert.equal(await fs.readFile(path.join(f.request.workspace,'result.md'),'utf8'),'verified remote artifact');
 const sent=JSON.parse(service.received);assert.equal(sent.context[0].text,'controlled source only');assert.equal(sent.context[0].sha256,f.request.inputs[0].sha256);assert.equal(sent.outputFiles[0],'result.md');assert.deepEqual(result.readPaths,[]);
 assert.equal(f.ledger.snapshot().tokens,5000);assert.equal(f.ledger.snapshot().costMicros,'UNKNOWN');assert.equal(service.calls,1);
});
test('local dispatch preserved; remote reviewer, manager, URLs and ungranted writes denied before registry',async t=>{
 const f=await fixture(t);let calls=0;const r=router(async()=>{calls++;throw Error('should not resolve');});
 assert.equal((await r.execute({...f.request,executorId:'local'})).local,true);
 for(const patch of [{role:'reviewer'},{role:'manager'},{executorId:'https://arbitrary'},{permissions:['read']},{writePaths:['../escape']},{writePaths:['inputs/escape']}])await assert.rejects(r.execute({...f.request,...patch}));
 assert.equal(calls,0);
});
test('changed, oversized, or arbitrary context never reaches remote registry',async t=>{
 const f=await fixture(t);let calls=0;const r=router(async()=>{calls++;});
 await assert.rejects(r.execute({...f.request,inputs:[{...f.request.inputs[0],sha256:'0'.repeat(64)}]}),/HASH/);
 await assert.rejects(r.execute({...f.request,inputs:[{path:'outside.txt',name:'x',sha256:'0'.repeat(64)}]}),/CONTEXT/);
 await fs.writeFile(path.join(f.request.workspace,'inputs','large.txt'),'a'.repeat(65537));
 await assert.rejects(r.execute({...f.request,inputs:[{path:'inputs/large.txt',name:'x',sha256:sha('a'.repeat(65537))}]}));assert.equal(calls,0);
});
test('trusted binding checks every identity field and same root ledger before execute',async t=>{
 const f=await fixture(t);let executions=0,closed=0;
 for(const patch of [{identity:{...f.request.identity,sessionId:'another'}},{identity:{...f.request.identity,parentTaskId:'another'}},{identity:{...f.request.identity,agentId:'another'}},{ledger:new BudgetLedger({maxRequests:10,maxTokens:100000})}]){
  const r=router(async()=>({binding:{identity:f.request.identity,ledger:f.ledger,artifactRoot:f.artifactRoot,...patch},execute:async()=>{executions++;},close:async()=>{closed++;}}));assert.equal((await r.execute(f.request)).error,'A2A_TEAM_BINDING_DENIED');
 }assert.equal(executions,0);assert.equal(closed,4);
});
test('real remote failed state preserved; no artifact copied and unknown reservation retained',async t=>{
 const f=await fixture(t),service=await ref(t,{state:TaskState.TASK_STATE_FAILED});const result=await router(registry(f,service)).execute(f.request);
 assert.equal(result.status,'FAILED');await assert.rejects(fs.stat(path.join(f.request.workspace,'result.md')));assert.equal(f.ledger.snapshot().tokens,5000);
});
test('actual remote credentials account and permission grants fail closed',async t=>{
 for(const options of [{credentialOwner:'another-account'},{permissions:['read','write','bash']}]){
  const f=await fixture(t),service=await ref(t);const result=await router(registry(f,service,options)).execute(f.request);assert.equal(result.status,'FAILED');assert.equal(service.calls,0);assert.equal(f.ledger.snapshot().requests,0);
 }
});
test('actual artifact hash, task binding and declared names verified before copying',async t=>{
 for(const mutateRef of [x=>({...x,sha256:'0'.repeat(64)}),x=>({...x,name:'unapproved.md'}),x=>({...x,taskId:'foreign'}),x=>({...x,internalPath:path.join(os.tmpdir(),'outside.txt')})]){
  const f=await fixture(t),service=await ref(t);const result=await router(registry(f,service,{mutateRef})).execute(f.request);assert.equal(result.status,'FAILED');await assert.rejects(fs.stat(path.join(f.request.workspace,'result.md')));
 }
});
test('existing output never overwritten; hardlinked stored artifact rejected',async t=>{
 const f=await fixture(t),service=await ref(t);await fs.writeFile(path.join(f.request.workspace,'result.md'),'existing');assert.equal((await router(registry(f,service)).execute(f.request)).status,'FAILED');assert.equal(await fs.readFile(path.join(f.request.workspace,'result.md'),'utf8'),'existing');
 const g=await fixture(t);const file=path.join(g.artifactRoot,'result.txt');await fs.writeFile(file,'value');await fs.link(file,path.join(g.artifactRoot,'alias.txt'));
 const r=router(async()=>({binding:{identity:g.request.identity,ledger:g.ledger,artifactRoot:g.artifactRoot},execute:async()=>({status:'SUCCEEDED',artifacts:[{taskId:g.request.identity.taskId,name:'result.md',size:5,sha256:sha('value'),internalPath:file}]}),close:async()=>{}}));assert.equal((await r.execute(g.request)).error,'A2A_TEAM_ARTIFACT_DENIED');
});
test('remote cancellation returns cancelled and retains unknown reservation without materialization',async t=>{
 const f=await fixture(t),abort=new AbortController();const service=await ref(t,{onStart:()=>setTimeout(()=>abort.abort(),20)});const result=await router(registry(f,service)).execute({...f.request,signal:abort.signal});
 assert.equal(result.status,'CANCELLED');await assert.rejects(fs.stat(path.join(f.request.workspace,'result.md')));assert.equal(f.ledger.snapshot().tokens,5000);
});

test('symlink directories, out-of-session staging and URL references never materialize',async t=>{
 const f=await fixture(t),outside=path.join(f.root,'outside');await fs.mkdir(outside);await fs.writeFile(path.join(outside,'data.txt'),'value');
 await fs.symlink(outside,path.join(f.request.workspace,'inputs','linked'),process.platform==='win32'?'junction':'dir');
 await assert.rejects(router(async()=>{}).execute({...f.request,inputs:[{name:'data.txt',path:'inputs/linked/data.txt',sha256:sha('value')}]}),/LINK/);
 let executed=0;const r=router(async()=>({binding:{identity:f.request.identity,ledger:f.ledger,artifactRoot:outside},execute:async()=>{executed++;},close:async()=>{}}));assert.equal((await r.execute(f.request)).error,'A2A_TEAM_STATE_DENIED');assert.equal(executed,0);
 const service=await ref(t);assert.equal((await router(registry(f,service,{mutateRef:x=>({...x,url:'http://169.254.169.254/'})})).execute(f.request)).status,'FAILED');await assert.rejects(fs.stat(path.join(f.request.workspace,'result.md')));
});
test('failed and partial statuses remain explicit and cleanup failure cannot mask them',async t=>{
 const f=await fixture(t);
 for(const status of ['FAILED','CANCELLED','PARTIAL']){
  const r=router(async()=>({binding:{identity:f.request.identity,ledger:f.ledger,artifactRoot:f.artifactRoot},execute:async()=>({status,artifacts:[]}),close:async()=>{throw Error('cleanup failure');}}));assert.equal((await r.execute(f.request)).status,status);
 }
});

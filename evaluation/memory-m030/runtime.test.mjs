import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';
import {execute} from './run.mjs';
import {claimAuthorization} from './guards.mjs';
const here=path.dirname(fileURLToPath(import.meta.url));
const tasks=JSON.parse(await fs.readFile(path.join(here,'tasks.json')));
async function temporary(t){const root=await fs.mkdtemp(path.join(os.tmpdir(),'m030-runtime-'));t.after(async()=>{assert.equal(path.dirname(root),path.resolve(os.tmpdir()));assert.ok(path.basename(root).startsWith('m030-runtime-'));await fs.rm(root,{recursive:true,force:true});});return root;}
function responder({wrongFile=false,denyProbe=false,skipTargetRead=false}={}) {
 const counts=new Map();
 return (body,{summary,taskId,stage,group})=>{
  if(summary)return '## Progress\nSynthetic neutral summary for engineering path coverage only.\n## Next\nFollow current task.';
  if(stage===1)return '阶段一已记录';
  const task=tasks.find(t=>t.id===taskId);
  if(!task.artifact)return '```json\n'+JSON.stringify(task.expected)+'\n```\n已依据提供的信息回答。';
  const id=taskId+group,n=(counts.get(id)??0)+1;counts.set(id,n);
  if(n===1)return {tools:[{name:'read',arguments:{path:'README.md'}},...(skipTargetRead?[]:[{name:'read',arguments:{path:task.artifact.path}}])]};
  if(n===2)return {tools:[{name:'write',arguments:{path:denyProbe?'forbidden.json':task.artifact.path,content:JSON.stringify(wrongFile?task.artifact.initial:task.artifact.expected)}}]};
  return JSON.stringify(task.expected);
 };
}
test('M030-R1 actual production tool write, two compacted stages, four arms, bounded retrieval and no gold/prohibited leak',{timeout:120000},async t=>{
 const root=await temporary(t),result=await execute({mode:'offline',runDirectory:root,responder:responder()});
 assert.equal(result.rows.length,16);assert.equal(result.summary.quality,null);assert.equal(result.summary.kind,'engineering-only-not-model-quality');
 assert.equal(result.summary.budget.requests,72);assert.ok(result.summary.budget.reservedTokens<=3072000);
 for(const row of result.rows){
  assert.equal(row.status,'completed',JSON.stringify({id:row.taskId,group:row.group,status:row.status,failure:row.failureCategory,denied:row.budgetDenial}));
  assert.equal(row.compactions,2);assert.deepEqual(row.checkpoints.map(c=>c.stage),[1,2]);assert.notEqual(row.checkpoints[0].historySha256,row.checkpoints[1].historySha256);
  assert.ok(row.requests<=8&&row.reservedTokens<=192000);assert.ok(row.memoryEvents.every(e=>e.bytes<=1024&&e.count<=4));
  if(row.taskId==='N01'){assert.equal(row.artifactPassed,true);assert.ok(row.tools.some(x=>x.name==='write'&&x.ok));}
  else{assert.equal(row.score.content.correct,true);assert.equal(row.score.format.compliant,false);}
 }
 for(const [i,record] of result.metadata.entries()){
  assert.equal(record.actualMaxOutputTokens,2048);assert.equal(record.httpStatus,200);
  const body=result.offlineRequests[i].body,text=JSON.stringify(body),task=tasks.find(x=>x.id===record.taskId);
  assert.ok(!body.tools?.some(x=>x.function.name==='bash'));
  for(const word of task.prohibited){const found=/^\d+$/.test(word)?new RegExp('(?<![A-Za-z0-9])'+word+'(?![A-Za-z0-9])').test(text):text.includes(word);assert.ok(!found,'prohibited source leaked to model '+task.id);}
  assert.ok(!text.includes('"prohibited"')&&!text.includes('"expected"'));
  if(!record.summary){assert.equal(body.tools.some(x=>x.function.name==='recall'),['B','D'].includes(record.group));}
 }
 const journal=(await fs.readFile(path.join(root,'requests.jsonl'),'utf8')).trim().split('\n').map(JSON.parse);assert.equal(journal.filter(x=>x.type==='REQUEST_STARTED').length,72);assert.equal(journal.filter(x=>x.type==='REQUEST_FINISHED').length,72);for(const item of journal.filter(x=>x.type==='REQUEST_FINISHED'))assert.ok(journal.findIndex(x=>x.type==='REQUEST_STARTED'&&x.requestId===item.requestId)<journal.indexOf(item));
 assert.equal(result.metadata.filter(r=>r.summary).length,32);
 await assert.rejects(execute({mode:'offline',runDirectory:root,responder:responder()}),{code:'EEXIST'});
 t.diagnostic(JSON.stringify({externalRequests:0,loopbackRequests:72,actualCompactions:32,reservedTokens:result.summary.budget.reservedTokens}));
});
test('M030-R2 unsafe file request is denied and prose success cannot pass artifact grading',{timeout:120000},async t=>{
 for(const option of [{denyProbe:true},{skipTargetRead:true}]) {
  const root=await temporary(t),reply=responder(option);
  const result=await execute({mode:'offline',runDirectory:root,responder:(body,info)=>info.group==='A'&&info.taskId==='N01'?reply(body,info):({text:'stop after first negative case',finishReason:'length'})});
  const row=result.rows[0];assert.equal(row.status,'completed');assert.equal(row.score.content.correct,true);assert.equal(row.artifactPassed,false);
  if(option.denyProbe)assert.ok(row.tools.some(x=>x.path==='forbidden.json'&&x.approved===false));
  if(option.skipTargetRead)assert.equal(row.artifact.score.pass,true,'Even correct file fails without required target read');
 }

});
test('M030-R3 truncated first summary stops all later paid-shaped instances',{timeout:30000},async t=>{
 const root=await temporary(t),result=await execute({mode:'offline',runDirectory:root,responder:()=>({text:'unfinished',finishReason:'length',thinking:'DO_NOT_PERSIST_HIDDEN'})});
 assert.equal(result.summary.budget.requests,1);assert.equal(result.rows[0].failureCategory,'summary_output_cap');assert.equal(result.rows[0].compactions,0);
 assert.equal(result.rows.filter(x=>x.status==='not_run_prior_failure').length,15);assert.ok(!JSON.stringify(result.metadata).includes('DO_NOT_PERSIST_HIDDEN'));
});
test('M030-R4 real execution needs explicit approval and fixed authorization prevents changed-directory replay',async t=>{
 const root=await temporary(t),auth=path.join(root,'approval.json'),run=path.join(root,'real'),ledger=path.join(root,'ledger');
 const protocol={id:'memory-m030-v1',budget:{maxRequests:128,maxReservedTokens:3072000}};
 await assert.rejects(claimAuthorization({runDirectory:run,protocol,frozenSha256:'hash',endpointSha256:'endpoint-hash'}),/required/);
 const a={status:'PENDING_USER_APPROVAL',batchId:protocol.id,runDirectory:run,ledgerDirectory:ledger,maxRequests:128,maxReservedTokens:3072000,frozenSha256:'hash',endpointSha256:'endpoint-hash'};
 await fs.writeFile(auth,JSON.stringify(a));await assert.rejects(claimAuthorization({authorizationPath:auth,runDirectory:run,protocol,frozenSha256:'hash',endpointSha256:'endpoint-hash'}),/missing/);
 a.status='APPROVED';await fs.writeFile(auth,JSON.stringify(a));await assert.rejects(claimAuthorization({authorizationPath:auth,runDirectory:path.join(root,'other'),protocol,frozenSha256:'hash',endpointSha256:'endpoint-hash'}),/directory/);
 await claimAuthorization({authorizationPath:auth,runDirectory:run,protocol,frozenSha256:'hash',endpointSha256:'endpoint-hash'});
 await assert.rejects(claimAuthorization({authorizationPath:auth,runDirectory:run,protocol,frozenSha256:'hash',endpointSha256:'endpoint-hash'}),{code:'EEXIST'});
});

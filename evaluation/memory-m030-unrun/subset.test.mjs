import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {execute} from './run.mjs';

const here=path.dirname(fileURLToPath(import.meta.url)),parent=path.resolve(here,'../memory-m030');
const read=name=>fs.readFile(path.join(here,name),'utf8');
const tasks=JSON.parse(await read('tasks.json')),protocol=JSON.parse(await read('protocol.json'));
const parentTasks=JSON.parse(await fs.readFile(path.join(parent,'tasks.json'),'utf8'));
const parentProtocol=JSON.parse(await fs.readFile(path.join(parent,'protocol.json'),'utf8'));
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');

test('unrun subset preserves canonical bytes of each original task and excludes every N01 group',()=>{
 assert.deepEqual(tasks.map(t=>t.id),['N02','N03','N04']);
 assert.deepEqual(protocol.taskOrder,['N02','N03','N04']);
 assert.deepEqual(Object.keys(protocol.executionOrders),protocol.taskOrder);
 assert.deepEqual(protocol.artifactTaskIds,[]);
 for(const task of tasks){
  assert.equal(JSON.stringify(task),JSON.stringify(parentTasks.find(t=>t.id===task.id)));
  assert.deepEqual(protocol.executionOrders[task.id],parentProtocol.executionOrders[task.id]);
  assert.equal(task.artifact,undefined);
 }
 assert.deepEqual(protocol.groups,parentProtocol.groups);
 assert.deepEqual(protocol.model,parentProtocol.model);
 for(const field of ['contextWindow','memoryBudgetBytes','memoryLimit','stages','compactionsPerInstance'])assert.equal(protocol[field],parentProtocol[field]);
});

test('unrun budget and inherited scope remain wholly inside parent authorization',async()=>{
 assert.equal(protocol.budget.maxRequests,96);assert.equal(protocol.budget.maxReservedTokens,2304000);
 for(const [key,value] of Object.entries(parentProtocol.budget))if(!['maxRequests','maxReservedTokens'].includes(key))assert.equal(protocol.budget[key],value);
 const p=protocol.parent;
 assert.equal(p.consumedRequests,7);assert.equal(p.consumedReservedTokens,176892);
 assert.equal(p.combinedMaxRequests,protocol.budget.maxRequests+p.consumedRequests);
 assert.equal(p.combinedMaxReservedTokens,protocol.budget.maxReservedTokens+p.consumedReservedTokens);
 assert.ok(p.combinedMaxRequests<=parentProtocol.budget.maxRequests);
 assert.ok(p.combinedMaxReservedTokens<=parentProtocol.budget.maxReservedTokens);
 assert.equal(p.requireCompletedMarker,true);assert.deepEqual(p.requireNoStartedRequestsFor,protocol.taskOrder);assert.deepEqual(p.excludedTaskIds,['N01']);
 assert.equal(p.frozenSha256,hash(await fs.readFile(path.join(parent,'frozen.json'))));
 for(const [name,digest] of Object.entries(p.sourceHashes))assert.equal(hash(await fs.readFile(path.join(parent,name))),digest);
});

test('unrun copied modules permit only zero accounting and enforced parent guard integration',async()=>{
 for(const name of ['guards.mjs','scoring.mjs','fixtures.mjs','offline.mjs'])assert.equal(hash(await fs.readFile(path.join(here,name))),hash(await fs.readFile(path.join(parent,name))),name);
 const original=await fs.readFile(path.join(parent,'run.mjs'),'utf8');
 let patch=original.replace('requests:0,inputTokens:0,outputTokens:0,latencyMs:0,compactions:0,reservedTokens:0','requests:0,inputTokens:0,outputTokens:0,knownInputTokens:0,knownOutputTokens:0,unknownUsageRequests:0,latencyMs:0,compactions:0,reservedTokens:0');
 patch=patch.replace("import {offlineServer} from './offline.mjs';","import {offlineServer} from './offline.mjs';\nimport {verifyParent,claimParentSubset} from './parent.mjs';")
 .replace('let ledger=null;','let ledger=null,parentAudit=null;')
 .replace("  ledger=await claimAuthorization({authorizationPath,runDirectory,protocol,frozenSha256:freeze.sha256,endpointSha256:sha256(env.CODING_MODEL_BASE_URL)});","  parentAudit=await verifyParent({authorizationPath,runDirectory,protocol,frozenSha256:freeze.sha256,endpointSha256:sha256(env.CODING_MODEL_BASE_URL),here});\n  ledger=await claimAuthorization({authorizationPath,runDirectory,protocol,frozenSha256:freeze.sha256,endpointSha256:sha256(env.CODING_MODEL_BASE_URL)});\n  await claimParentSubset(parentAudit);")
 .replace('authorizationLedger:ledger?path.basename(ledger):null,budget:','authorizationLedger:ledger?path.basename(ledger):null,parentVerification:parentAudit?.safe??null,budget:');
 patch=patch.replace(/\r?\n/g,original.includes('\r\n')?'\r\n':'\n');
 assert.notEqual(patch,original);assert.equal(await read('run.mjs'),patch);
});

test('unrun real SDK offline path: all 12 instances, 48 loopback requests, 24 actual compactions',{timeout:120000},async t=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'m030-unrun-subset-'));
 t.after(async()=>{assert.equal(path.dirname(root),path.resolve(os.tmpdir()));assert.ok(path.basename(root).startsWith('m030-unrun-subset-'));await fs.rm(root,{recursive:true,force:true});});
 const result=await execute({mode:'offline',runDirectory:root,responder:(body,{summary,stage,taskId})=>{
  if(summary)return '## Progress\nSynthetic neutral summary for engineering coverage only.\n## Next\nFollow latest task.';
  if(stage===1)return '阶段一已记录';
  return JSON.stringify(tasks.find(task=>task.id===taskId).expected);
 }});
 assert.equal(result.rows.length,12);assert.equal(result.summary.kind,'engineering-only-not-model-quality');assert.equal(result.summary.quality,null);
 assert.equal(result.summary.budget.requests,48);assert.ok(result.summary.budget.reservedTokens<=2304000);
 for(const row of result.rows){
  assert.equal(row.status,'completed',JSON.stringify({taskId:row.taskId,group:row.group,status:row.status,failure:row.failureCategory,denied:row.budgetDenial}));
  assert.ok(protocol.taskOrder.includes(row.taskId));assert.equal(row.requests,4);assert.equal(row.compactions,2);
  assert.deepEqual(row.checkpoints.map(c=>c.stage),[1,2]);assert.notEqual(row.checkpoints[0].historySha256,row.checkpoints[1].historySha256);
  assert.equal(row.score.content.correct,true);assert.equal(row.artifactPassed,null);assert.ok(row.tools.length===0);
  assert.ok(row.memoryEvents.every(event=>event.bytes<=1024&&event.count<=4));
  assert.equal(row.inputTokens,0);assert.equal(row.outputTokens,0);assert.equal(row.unknownUsageRequests,0);assert.equal(row.usageIsSynthetic,true);
 }
 assert.equal(result.metadata.filter(record=>record.summary).length,24);
 assert.equal(result.metadata.length,48);assert.equal(result.offlineRequests.length,48);
 for(const [index,record] of result.metadata.entries()){
  assert.equal(record.actualMaxOutputTokens,2048);assert.equal(record.httpStatus,200);
  const body=result.offlineRequests[index].body,text=JSON.stringify(body),task=tasks.find(value=>value.id===record.taskId);
  assert.ok(!body.tools?.some(tool=>tool.function.name==='bash'));
  assert.ok(!text.includes('"prohibited"')&&!text.includes('"expected"'));
  for(const word of task.prohibited){const found=/^\d+$/.test(word)?new RegExp('(?<![A-Za-z0-9])'+word+'(?![A-Za-z0-9])').test(text):text.includes(word);assert.ok(!found,'prohibited source leaked '+task.id);}
  if(!record.summary)assert.equal(body.tools.some(tool=>tool.function.name==='recall'),['B','D'].includes(record.group));
 }
 const journal=(await fs.readFile(path.join(root,'requests.jsonl'),'utf8')).trim().split('\n').map(JSON.parse);
 assert.equal(journal.filter(record=>record.type==='REQUEST_STARTED').length,48);assert.equal(journal.filter(record=>record.type==='REQUEST_FINISHED').length,48);
 assert.ok(journal.every(record=>protocol.taskOrder.includes(record.taskId)));
 await assert.rejects(execute({mode:'offline',runDirectory:root,responder:()=>''}),{code:'EEXIST'});
 t.diagnostic(JSON.stringify({externalRequests:0,loopbackRequests:48,actualCompactions:24,instances:12,reservedTokens:result.summary.budget.reservedTokens}));
});


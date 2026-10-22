import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {verifyParent,claimParentSubset} from './parent.mjs';
import {sha256} from './guards.mjs';

const here=path.dirname(fileURLToPath(import.meta.url));
const source=path.resolve(here,'../memory-m030');
const sourceNames=['protocol.json','tasks.json','run.mjs','guards.mjs','scoring.mjs','fixtures.mjs','offline.mjs','frozen.json'];
async function fixture(t){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'m030-parent-proof-'));
 t.after(async()=>{assert.equal(path.dirname(root),path.resolve(os.tmpdir()));assert.ok(path.basename(root).startsWith('m030-parent-proof-'));await fs.rm(root,{recursive:true,force:true});});
 const parentDirectory=path.join(root,'parent'),parentSource=path.join(parentDirectory,'evaluation','memory-m030'),childHere=path.join(root,'child');
 await fs.mkdir(parentSource,{recursive:true});await fs.mkdir(path.join(parentDirectory,'real-1'),{recursive:true});await fs.mkdir(childHere);
 for(const name of sourceNames)await fs.copyFile(path.join(source,name),path.join(parentSource,name));
 await fs.copyFile(path.join(here,'tasks.json'),path.join(childHere,'tasks.json'));
 const protocol=JSON.parse(await fs.readFile(path.join(here,'protocol.json'),'utf8'));
 const endpointSha256='e'.repeat(64),frozenSha256='c'.repeat(64);
 const authorizationPath=path.join(childHere,'authorization.json'),runDirectory=path.join(childHere,'real-1');
 const parentAuthorizationPath=path.join(parentDirectory,'authorization.json'),completedPath=path.join(parentDirectory,'real-1','COMPLETED'),journalPath=path.join(parentDirectory,'real-1','requests.jsonl');
 const parentAuthorization={status:'APPROVED',batchId:'memory-m030-v1',frozenSha256:protocol.parent.frozenSha256,endpointSha256,runDirectory:path.join(parentDirectory,'real-1'),ledgerDirectory:path.join(parentDirectory,'ledger'),maxRequests:128,maxReservedTokens:3072000};
 await fs.writeFile(parentAuthorizationPath,JSON.stringify(parentAuthorization));await fs.writeFile(completedPath,'2026-10-22T00:00:00.000Z');
 const rows=[];
 for(let i=0;i<7;i++){
  const requestId=randomUUID(),reserve=i<6?25000:26892,stage=i<3?1:2;
  rows.push({type:'REQUEST_STARTED',requestId,taskId:'N01',group:'A',stage,requestBytes:reserve-2048,reservedTokens:reserve,actualMaxOutputTokens:2048,summary:i===0||i===3});
  rows.push({type:'REQUEST_FINISHED',requestId,taskId:'N01',group:'A',stage,inputTokens:100,outputTokens:20,status:'SUCCEEDED'});
 }
 const child={status:'APPROVED',batchId:protocol.id,frozenSha256,endpointSha256,runDirectory,ledgerDirectory:path.join(childHere,'ledger'),maxRequests:96,maxReservedTokens:2304000,parentDirectory};
 const saveChild=()=>fs.writeFile(authorizationPath,JSON.stringify(child));
 async function refresh(){
  child.parentAuthorizationSha256=sha256(await fs.readFile(parentAuthorizationPath));
  child.parentCompletedSha256=sha256(await fs.readFile(completedPath));
  child.parentRequestsSha256=sha256(await fs.readFile(journalPath));
  await saveChild();
 }
 async function saveJournal(value=rows,{newline=true,pin=true}={}){
  await fs.writeFile(journalPath,value.map(row=>JSON.stringify(row)).join('\n')+(newline?'\n':''));
  if(pin)await refresh();
 }
 await saveJournal();
 return {root,parentDirectory,parentSource,childHere,protocol,rows,child,parentAuthorization,parentAuthorizationPath,completedPath,journalPath,saveJournal,refresh,saveChild,args:{authorizationPath,runDirectory,protocol,frozenSha256,endpointSha256,here:childHere}};
}

test('parent valid proof counts all starts even without finished or known token usage',async t=>{
 const f=await fixture(t),audit=await verifyParent(f.args);
 assert.equal(audit.safe.consumedRequests,7);assert.equal(audit.safe.consumedReservedTokens,176892);assert.equal(audit.safe.combinedMaxRequests,103);assert.equal(audit.safe.combinedMaxReservedTokens,2480892);
 assert.equal(audit.safe.unknownUsageRequests,0);assert.equal(Object.hasOwn(audit.safe,'parentDirectory'),false);
 f.rows.pop();f.rows[1].inputTokens=null;f.rows[1].outputTokens=null;f.rows[1].status='UNKNOWN';await f.saveJournal();
 const unknown=await verifyParent(f.args);assert.equal(unknown.safe.finishedRequests,6);assert.equal(unknown.safe.unknownUsageRequests,2);assert.equal(unknown.safe.consumedReservedTokens,176892);
});

test('parent pinned journal rejects tamper or incomplete final row',async t=>{
 const f=await fixture(t);await fs.appendFile(f.journalPath,' ');
 await assert.rejects(verifyParent(f.args),/journal hash mismatch/);
 await f.saveJournal(f.rows,{newline:false});
 await assert.rejects(verifyParent(f.args),/partial journal/);
});

test('parent rejects duplicate start, bad event, invalid reservations and wrong types',async t=>{
 for(const modify of [
  rows=>rows.push({...rows[0]}),
  rows=>{rows[0].type='UNKNOWN_EVENT';},
  rows=>{rows[0].reservedTokens='25000';},
  rows=>{rows[0].requestBytes=0;},
  rows=>{rows[0].reservedTokens++;},
  rows=>{rows[0].actualMaxOutputTokens=4096;},
  rows=>{rows[0].summary='true';}
 ]){
  const f=await fixture(t);modify(f.rows);await f.saveJournal();await assert.rejects(verifyParent(f.args),/Parent verification/);
 }
});

test('parent rejects unmatched or duplicate finish and malformed usage',async t=>{
 for(const modify of [
  rows=>rows.push({...rows[1]}),
  rows=>{rows[1].requestId=randomUUID();},
  rows=>{rows[1].inputTokens='100';},
  rows=>{rows[1].outputTokens=-1;},
  rows=>{rows[1].status='UNRECOGNIZED';},
  rows=>{rows[1].stage=2;}
 ]){
  const f=await fixture(t);modify(f.rows);await f.saveJournal();await assert.rejects(verifyParent(f.args),/Parent verification/);
 }
});

test('parent rejects missing completion, already claimed child, started subset or non-N01-A cell',async t=>{
 const missing=await fixture(t);await fs.unlink(missing.completedPath);await assert.rejects(verifyParent(missing.args));
 const claimed=await fixture(t);await fs.writeFile(path.join(claimed.parentDirectory,'UNRUN_SUBSET_CLAIMED'),'prior');await assert.rejects(verifyParent(claimed.args),/prior child claim/);
 for(const [taskId,group] of [['N02','A'],['N03','C'],['N04','D'],['N01','B'],['N01','C'],['N01','D']]){
  const f=await fixture(t);f.rows[0].taskId=taskId;f.rows[0].group=group;f.rows[1].taskId=taskId;f.rows[1].group=group;await f.saveJournal();await assert.rejects(verifyParent(f.args),/already started|unexpected parent cell/);
 }
});

test('parent rejects authorization, endpoint, budget, source freeze and task changes',async t=>{
 for(const modify of [
  async f=>{f.child.status='PENDING';await f.saveChild();},
  async f=>{f.child.frozenSha256='f'.repeat(64);await f.saveChild();},
  async f=>{f.child.endpointSha256='f'.repeat(64);await f.saveChild();},
  async f=>{f.protocol.budget.maxReservedTokens=2400000;},
  async f=>{f.protocol.budget.perInstanceRequests=9;},
  async f=>{f.protocol.model.model='different-model';},
  async f=>{f.protocol.executionOrders.N02.reverse();},
  async f=>{f.parentAuthorization.maxRequests=129;await fs.writeFile(f.parentAuthorizationPath,JSON.stringify(f.parentAuthorization));await f.refresh();},
  async f=>{await fs.appendFile(path.join(f.parentSource,'frozen.json'),' ');},
  async f=>{const tasks=JSON.parse(await fs.readFile(path.join(f.childHere,'tasks.json')));tasks[0].prompt+=' altered';await fs.writeFile(path.join(f.childHere,'tasks.json'),JSON.stringify(tasks));},
  async f=>{f.rows.pop();f.rows.pop();await f.saveJournal();}
 ]){
  const f=await fixture(t);await modify(f);await assert.rejects(verifyParent(f.args),/Parent verification/);
 }
});

test('parent claim is atomic, durable and cannot be replayed under another child run path',async t=>{
 const f=await fixture(t),audit=await verifyParent(f.args);
 const result=await claimParentSubset(audit);assert.equal(result.childId,'memory-m030-unrun-v1');
 const saved=JSON.parse(await fs.readFile(audit.claimFile,'utf8'));assert.equal(saved.consumedRequests,7);assert.ok(saved.claimedAt);
 await assert.rejects(claimParentSubset(audit),{code:'EEXIST'});
 f.child.runDirectory=path.join(f.childHere,'other-run');f.args.runDirectory=f.child.runDirectory;await f.saveChild();
 await assert.rejects(verifyParent(f.args),/prior child claim/);
});


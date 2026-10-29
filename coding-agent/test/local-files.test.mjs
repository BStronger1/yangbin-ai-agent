import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { LocalToolRuntime } from '../src/local-tools.mjs';

async function fixture(t, extra = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'local-tools-'));
  const events = [], abort = new AbortController();
  const tools = new LocalToolRuntime({ workspace: root, emit:e=>events.push(e), signal:abort.signal, ...extra });
  t.after(async()=>{tools.cancel();await fs.rm(root,{recursive:true,force:true});});
  return { tools, root, events, abort };
}
test('local init pings browser; read pages remote content and never falls back to managed files', async t => {
  const {tools,root,events}=await fixture(t);
  await fs.writeFile(path.join(root,'secret.txt'),'server sentinel');
  const init=tools.init();while(!events.find(e=>e.type==='local_fs'))await new Promise(r=>setTimeout(r,5));
  tools.localResult({requestId:events.at(-1).requestId,ok:true,output:'connected'});await init;
  assert.equal(tools.allowShell,false);
  const pending=tools.execute('read',{path:'secret.txt',limit:1});
  await new Promise(r=>setTimeout(r,0));const request=events.findLast(e=>e.type==='local_fs');
  tools.localResult({requestId:request.requestId,ok:true,output:'local first\nlocal second\n'});
  const result=await pending;assert.match(result.content[0].text,/local first/);assert.doesNotMatch(result.content[0].text,/server sentinel/);assert.equal(result.details.page.nextOffset,1);
  assert.equal(await fs.readFile(path.join(root,'secret.txt'),'utf8'),'server sentinel');
});
test('local write approval precedes browser request; refusal never sends mutation',async t=>{
  const {tools,events}=await fixture(t);
  const promise=tools.execute('write',{path:'a.txt',content:'new'});
  const approval=events.find(e=>e.type==='approval_required');assert.ok(approval);assert.equal(events.some(e=>e.type==='local_fs'),false);
  tools.approve(approval.approvalId,false);await assert.rejects(promise,/rejected/);assert.equal(events.some(e=>e.type==='local_fs'),false);
});
test('approved local write is single request and duplicate result has no effect',async t=>{
  const {tools,events}=await fixture(t);
  const promise=tools.execute('write',{path:'a.txt',content:'new'});
  tools.approve(events.at(-1).approvalId,true);await new Promise(r=>setTimeout(r,0));
  const request=events.at(-1);assert.equal(request.type,'local_fs');
  assert.equal(tools.localResult({requestId:request.requestId,ok:true,output:'Wrote a.txt'}),true);
  await promise;assert.equal(tools.localResult({requestId:request.requestId,ok:true,output:'again'}),false);
});
test('disconnect timeout aborts task and ignores late result; cancellation closes pending requests',async t=>{
  let limited=false;const {tools,events,abort}=await fixture(t,{localTimeoutMs:15,onLimit:()=>{limited=true;}});
  await assert.rejects(tools.execute('read',{path:'a'}),/No automatic replay/);assert.ok(limited);
  assert.equal(tools.localResult({requestId:events.find(e=>e.type==='local_fs').requestId,ok:true,output:'late'}),false);
  const pending=tools.execute('read',{path:'a'});await new Promise(r=>setTimeout(r,0));abort.abort();await assert.rejects(pending,/interrupted/);
});
test('local shell remains refused',async t=>{
  const {tools}=await fixture(t);await assert.rejects(tools.execute('bash',{command:'touch x'}),/unavailable/);
});

test('explicit allow automatically dispatches files and processes without approvals',async t=>{
 const {tools,events}=await fixture(t,{approvalMode:'allow'});tools.localProcesses=true;
 for(const name of ['read','write','edit','start','preview','stop','process_status']){
  const result=tools.execute(name,{path:'a',content:'x',command:'test',id:'job'});
  await new Promise(r=>setTimeout(r,0));
  const request=events.findLast(e=>e.type==='local_fs');assert.equal(request.operation,name);
  tools.localResult({requestId:request.requestId,ok:true,output:'ok'});await result;
 }
 assert.equal(events.some(e=>e.type==='approval_required'),false);
});
test('ask and on-risk continue to require approval for process starts',async t=>{
 for(const mode of ['ask','on-risk']){
  const {tools,events}=await fixture(t,{approvalMode:mode});tools.localProcesses=true;
  const promise=tools.execute('start',{command:'test'});
  const event=events.find(e=>e.type==='approval_required');assert.ok(event);
  tools.approve(event.approvalId,false);await assert.rejects(promise,/rejected/);
  assert.equal(events.some(e=>e.type==='local_fs'),false);
 }
});

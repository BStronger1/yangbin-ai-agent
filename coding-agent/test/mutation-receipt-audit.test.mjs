import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {LocalToolRuntime} from '../src/local-tools.mjs';
import {RepairVerification} from '../src/repair-verification.mjs';
import {toolOutcome} from '../src/tool-settlement.mjs';

const pause = () => new Promise(resolve => setImmediate(resolve));
async function fixture(t, extra={}) {
  const workspace=await fs.mkdtemp(path.join(os.tmpdir(),'receipt-audit-'));
  const events=[],abort=new AbortController();
  const tools=new LocalToolRuntime({workspace,approvalMode:'allow',signal:abort.signal,emit:event=>events.push(event),...extra});
  t.after(async()=>{tools.cancel();await fs.rm(workspace,{recursive:true,force:true});});
  return {tools,events,abort};
}
const outcome=(failure,extra={})=>toolOutcome({ok:false,sideEffect:true,dispatched:true,failure,...extra});
const capture=promise=>promise.then(()=>null,error=>error);

test('audit: concurrent replies settle only their own operation despite reverse arrival',async t=>{
  const {tools,events}=await fixture(t);
  const a=capture(tools.execute('edit',{path:'a.py',oldText:'x',newText:'y'},'edit-a'));
  const b=capture(tools.execute('edit',{path:'b.py',oldText:'x',newText:'y'},'edit-b'));
  await pause();
  const [ra,rb]=events.filter(event=>event.type==='local_fs');
  assert.equal(ra.arguments.path,'a.py');assert.equal(rb.arguments.path,'b.py');
  tools.localResult({requestId:rb.requestId,ok:false,output:'commit acknowledgement unavailable',mutationReceipt:{effect:'UNKNOWN',phase:'COMMITTING'}});
  tools.localResult({requestId:ra.requestId,ok:false,output:'predicate did not match',mutationReceipt:{effect:'NO_EFFECT',phase:'VALIDATING'}});
  assert.equal(outcome(await a),'NO_EFFECT');assert.equal(outcome(await b),'UNKNOWN_EFFECT');
  assert.equal(tools.localResult({requestId:rb.requestId,ok:false,output:'late',mutationReceipt:{effect:'NO_EFFECT',phase:'VALIDATING'}}),false);
});

test('audit: cancellation immediately after success retains committed effect and invalidates old verification',async t=>{
  const {tools,events,abort}=await fixture(t);
  const verification=new RepairVerification(()=>{});tools.verification=verification;
  const pending=capture(tools.execute('write',{path:'a.py',content:'new'},'cancel-after-write'));
  await pause();const request=events.find(event=>event.type==='local_fs');
  tools.localResult({requestId:request.requestId,ok:true,output:'Wrote a.py'});
  abort.abort(new Error('cancelled immediately after commit'));
  const failure=await pending;
  assert.equal(outcome(failure),'CONFIRMED');
  assert.equal(verification.revision,1);
  assert.equal(verification.changedPaths.has('a.py'),true);
});

for (const [label,mutationReceipt] of [
  ['old executor omitted effect',undefined],
  ['invalid no-effect commit phase',{effect:'NO_EFFECT',phase:'COMMITTING'}],
  ['invalid committed validating phase',{effect:'COMMITTED',phase:'VALIDATING'}],
]) test('audit: '+label+' cannot unblock an uncertain dispatched write',async t=>{
  const {tools,events}=await fixture(t);
  const pending=capture(tools.execute('write',{path:'a.py',content:'new'},label));
  await pause();const request=events.find(event=>event.type==='local_fs');
  tools.localResult({requestId:request.requestId,ok:false,output:'same arbitrary message',...(mutationReceipt?{mutationReceipt}:{})});
  assert.equal(outcome(await pending),'UNKNOWN_EFFECT');
});

test('audit: timeout retains uncertainty and ignores later no-effect claim',async t=>{
  let limits=0;
  const {tools,events}=await fixture(t,{localTimeoutMs:20,onLimit:()=>limits++});
  const failure=await capture(tools.execute('edit',{path:'a.py',oldText:'x',newText:'y'},'timed-out'));
  const request=events.find(event=>event.type==='local_fs');
  assert.equal(outcome(failure),'UNKNOWN_EFFECT');assert.equal(limits,1);
  assert.equal(tools.localResult({requestId:request.requestId,ok:false,output:'late',mutationReceipt:{effect:'NO_EFFECT',phase:'VALIDATING'}}),false);
  assert.equal(events.filter(event=>event.type==='local_fs').length,1);
});

test('audit: receipt comparison switch preserves frozen unknown-outcome behavior',()=>{
  const failure=Object.assign(new Error('predicate failed'),{mutationReceipt:{effect:'NO_EFFECT',phase:'VALIDATING'}});
  assert.equal(outcome(failure),'NO_EFFECT');
  assert.equal(outcome(failure,{useReceipts:false}),'UNKNOWN_EFFECT');
});

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import { ModelGate } from '../src/model-gate.mjs';
import { copySafeSnapshot, sandboxStatus, runIsolatedCommand } from '../src/sandbox.mjs';
import { runAgent } from '../src/agent.mjs';

const request={body:JSON.stringify({messages:[{role:'user',content:'中文预算'}],max_tokens:64})};
const stream=lines=>new Response(lines.map(x=>'data: '+(typeof x==='string'?x:JSON.stringify(x))+'\n\n').join(''),{headers:{'Content-Type':'text/event-stream'}});
test('governance denies before HTTP and approved requests reserve conservative bytes plus output',async()=>{
  let called=0; const events=[];let gate;
  gate=new ModelGate({required:true,emit:e=>{events.push(e);if(e.type==='model_request')gate.permission({...e,approved:false});}});
  await assert.rejects(gate.wrap(async()=>{called++;return stream(['[DONE]']);})('http://unused',request),/denied/);assert.equal(called,0);
  assert.equal(events[0].reservedTokens,Buffer.byteLength(request.body)+64);
  gate=new ModelGate({required:true,emit:e=>{events.push(e);if(e.type==='model_request')gate.permission({...e,approved:true});}});
  const response=await gate.wrap(async()=>{called++;return stream([{usage:{prompt_tokens:13,completion_tokens:7}},'[DONE]']);})('http://unused',request);
  await response.text();assert.equal(called,1);assert.deepEqual({...events.at(-1),requestId:null},{type:'model_usage',requestId:null,inputTokens:13,outputTokens:7,status:'SUCCEEDED'});
});
test('native Claude cache usage is counted and missing usage remains unknown rather than zero',async()=>{
  const events=[],gate=new ModelGate({emit:e=>events.push(e)});
  await (await gate.wrap(async()=>stream([{type:'message_start',message:{usage:{input_tokens:10,cache_read_input_tokens:20,cache_creation_input_tokens:3,output_tokens:0}}},{type:'message_delta',usage:{output_tokens:8}},{type:'message_stop'}]))('http://unused',request)).text();
  assert.equal(events.at(-1).inputTokens,33);assert.equal(events.at(-1).outputTokens,8);
  await (await gate.wrap(async()=>stream(['[DONE]']))('http://unused',request)).text();assert.equal(events.at(-1).inputTokens,null);assert.equal(events.at(-1).outputTokens,null);assert.equal(events.at(-1).status,'SUCCEEDED');
  await (await gate.wrap(async()=>stream([{choices:[]}]))('http://unused',request)).text();assert.equal(events.at(-1).status,'UNKNOWN');
});
test('SDK cancellation after terminal SSE preserves success with unknown usage; early cancellation does not',async()=>{
  const events=[],gate=new ModelGate({emit:e=>events.push(e)});
  for(const terminal of ['[DONE]', {type:'message_stop'}]) {
    const response=await gate.wrap(async()=>stream([terminal]))('http://unused',request);
    const reader=response.body.getReader();await reader.read();await reader.cancel();
    assert.equal(events.at(-1).status,'SUCCEEDED');assert.equal(events.at(-1).inputTokens,null);assert.equal(events.at(-1).outputTokens,null);
  }
  const interrupted=await gate.wrap(async()=>new Response(new ReadableStream({start(controller){controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":"partial"}}]}\n\n'));}})))('http://unused',request);
  const reader=interrupted.body.getReader();await reader.read();await reader.cancel();assert.equal(events.at(-1).status,'UNKNOWN');
  const failed=await gate.wrap(async()=>stream([{type:'error',error:{message:'simulated provider error'}},'[DONE]']))('http://unused',request);
  await failed.text();assert.equal(events.at(-1).status,'FAILED');
});
test('stream limits reject oversized complete lines and excessive total responses',async()=>{
  const events=[],gate=new ModelGate({emit:e=>events.push(e)});
  const oversized=await gate.wrap(async()=>stream([{content:'x'.repeat(1024*1024)}]))('http://unused',request);
  await assert.rejects(oversized.text(),/event exceeds limit/);assert.equal(events.at(-1).status,'UNKNOWN');
  const payload=new TextEncoder().encode(('data: '+JSON.stringify({content:'x'.repeat(1024)})+'\n\n').repeat(1000));
  let chunks=0;
  const total=await gate.wrap(async()=>new Response(new ReadableStream({pull(controller){if(++chunks<=10)controller.enqueue(payload);else controller.close();}})))('http://unused',request);
  await assert.rejects(total.text(),/response exceeds 8 MiB/);assert.equal(events.at(-1).status,'UNKNOWN');
});
test('admission timeout and abort do not issue HTTP',async()=>{
  const gate=new ModelGate({required:true,timeoutMs:5});let called=false;
  await assert.rejects(gate.wrap(async()=>{called=true;})('http://unused',request),/denied/);assert.equal(called,false);
  const controller=new AbortController(),pending=new ModelGate({required:true,signal:controller.signal});
  const promise=pending.wrap(async()=>{called=true;})('http://unused',request);controller.abort();await assert.rejects(promise);assert.equal(called,false);
});
test('safe snapshots exclude credentials and reject linked host paths',async t=>{
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'sandbox-test-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));
  const work=path.join(root,'work'),snap=path.join(root,'snap');await fs.mkdir(work);await fs.mkdir(snap);
  await fs.writeFile(path.join(work,'a.txt'),'visible');await fs.writeFile(path.join(work,'.env'),'secret');await fs.mkdir(path.join(work,'.git'));await fs.writeFile(path.join(work,'.git','config'),'secret');
  await copySafeSnapshot(work,snap);assert.deepEqual(await fs.readdir(snap),['a.txt']);
  await fs.link(path.join(work,'a.txt'),path.join(work,'linked'));await assert.rejects(copySafeSnapshot(work,snap),/linked/);
});
test('administrator isolation binary must be absolute and unavailable backend remains fail-closed',async()=>{
  const previous=process.env.CODING_BWRAP;
  try {
    process.env.CODING_BWRAP='relative/unsafe-runner';
    const status=await sandboxStatus({refresh:true});assert.equal(status.available,false);assert.match(status.reason,/absolute administrator-configured/);
    await assert.rejects(runIsolatedCommand({workspace:os.tmpdir(),command:'echo should-not-run'}),/isolation is required/);
  } finally { if(previous===undefined)delete process.env.CODING_BWRAP;else process.env.CODING_BWRAP=previous; }
});
test('Linux sandbox smoke enforces read-only snapshot, host path denial and temporary size bound',async t=>{
  const status=await sandboxStatus();if(!status.available){t.skip('Verified bwrap/prlimit unavailable; fail-closed path is covered separately');return;}
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'sandbox-live-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));
  await fs.writeFile(path.join(root,'source.txt'),'original');
  const result=await runIsolatedCommand({workspace:root,command:'test ! -e /home && test ! -e /etc && test ! -e /run && test ! -e /sys && ! touch /root-write 2>/dev/null && ! touch /usr/local/local-write 2>/dev/null && ! touch /dev/dev-write 2>/dev/null && touch /tmp/allowed-write && test "$(wc -l < /proc/net/route)" -eq 1 && ! echo changed > /workspace/source.txt && ! dd if=/dev/zero of=/tmp/limit bs=1048576 count=17 >/dev/null 2>&1 && echo isolated-ok'});
  assert.match(result,/Exit code: 0/);assert.match(result,/isolated-ok/);assert.equal(await fs.readFile(path.join(root,'source.txt'),'utf8'),'original');
});
test('real pi context compaction and session restoration across 26 synthetic turns are governed', {timeout:120000},async t=>{
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'coding-context-test-')),workspace=path.join(root,'workspace'),sessionDir=path.join(root,'session');await fs.mkdir(workspace);
  const requests=[];let summaries=0;
  const server=http.createServer(async(req,res)=>{
    let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);requests.push(body);
    const summary=JSON.stringify(body.messages).includes('<conversation>');if(summary)summaries++;
    const text=summary?'## Goal\nMaintain project marker ALPHA-26.\n## Progress\nCompleted earlier simulated turns.\n## Next Steps\nContinue current user request.':'Acknowledged project marker ALPHA-26. '+ 'Progress evidence. '.repeat(15);
    const usage={prompt_tokens:Math.ceil(JSON.stringify(body.messages).length/4),completion_tokens:Math.ceil(text.length/4)};
    res.writeHead(200,{'Content-Type':'text/event-stream'});
    res.write(`data: ${JSON.stringify({id:'context',object:'chat.completion.chunk',model:'offline',choices:[{index:0,delta:{role:'assistant',content:text},finish_reason:null}]})}\n\n`);
    res.write(`data: ${JSON.stringify({id:'context',object:'chat.completion.chunk',model:'offline',choices:[{index:0,delta:{},finish_reason:'stop'}],usage})}\n\n`);res.end('data: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));await fs.rm(root,{recursive:true,force:true});});
  const events=[];const env={CODING_MODEL_API_KEY:'loopback-only',CODING_MODEL_BASE_URL:`http://127.0.0.1:${server.address().port}/v1`,CODING_MODEL_MODEL:'offline',CODING_MODEL_ALLOW_PRIVATE:'true',CODING_GOVERNANCE_REQUIRED:'true',CODING_CONTEXT_WINDOW:'4096',CODING_OUTPUT_TOKENS:'256'};
  for(let i=0;i<26;i++){
    let gate;await runAgent({workspace,sessionDir,prompt:`Turn ${i+1}. Preserve ALPHA-26. ${'Synthetic task progress without external side effects. '.repeat(18)}`,approvalMode:'allow',allowShell:false},{signal:new AbortController().signal,onTools:()=>{},onGate:g=>{gate=g;},emit:e=>{events.push(e);if(e.type==='model_request')gate.permission({...e,approved:true});}},env);
  }
  t.diagnostic(`26 synthetic turns; ${summaries} summarization requests; ${requests.length} governed loopback model requests`);
  assert.ok(summaries>0,'actual SDK must call its compaction summarizer');
  assert.ok(requests.every(r=>(r.max_tokens??r.max_completion_tokens)>0&&(r.max_tokens??r.max_completion_tokens)<=256),'normal and summarization calls both obey the trusted output cap');
  assert.ok(events.some(e=>e.type==='context'&&e.status==='COMPACTED'));
  assert.ok(events.filter(e=>e.type==='context'&&e.status==='READY').slice(1).every(e=>e.messages>0),'new sessions restore persisted context');
  assert.equal(events.filter(e=>e.type==='model_request').length,requests.length,'every compaction and normal request needs admission');
  assert.equal(events.filter(e=>e.type==='model_usage').length,requests.length);
  assert.ok(events.filter(e=>e.type==='model_usage').every(e=>e.inputTokens>0&&e.outputTokens>0));
  const files=await fs.readdir(sessionDir);let history='';for(const file of files)history+=await fs.readFile(path.join(sessionDir,file),'utf8');
  assert.match(history,/"type":"compaction"/);assert.match(JSON.stringify(requests.at(-1).messages),/ALPHA-26/);
});

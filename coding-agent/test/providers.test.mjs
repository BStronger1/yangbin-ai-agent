import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import readline from 'node:readline';
import { spawn } from 'node:child_process';
import { createModelTransport, isPublicAddress } from '../src/model-transport.mjs';

const key = 'offline-byok-secret-123';
async function fixture(t, responder) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'coding-provider-test-'));
  const workspace = path.join(root, 'workspace'); await fs.mkdir(workspace);
  const requests = [];
  const server = http.createServer(async (req,res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const request = { url: req.url, headers: req.headers, body: raw ? JSON.parse(raw) : {} }; requests.push(request);
    responder(request, res, requests.length);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await fs.rm(root, {recursive:true,force:true}); });
  return { root, workspace, requests, url: `http://127.0.0.1:${server.address().port}` };
}
async function run(f, overrides = {}, prompt = 'Please create provider.txt with the expected text.') {
  const child = spawn(process.execPath, ['src/main.mjs'], { cwd: path.resolve(import.meta.dirname, '..'), env: { ...process.env, CODING_MODEL_API_KEY:key, CODING_MODEL_NAME:'custom-model-v9', CODING_MODEL_BASE_URL:f.url, CODING_MODEL_ALLOW_PRIVATE:'true', ...overrides }, windowsHide:true });
  let stderr = '', stdout = ''; const events=[];
  child.stderr.on('data', x => { stderr += x; }); child.stdout.on('data', x => { stdout += x; });
  const lines = readline.createInterface({input:child.stdout});
  lines.on('line', line => { const e=JSON.parse(line); events.push(e); if(e.type==='ready') child.stdin.write(JSON.stringify({type:'start',id:'turn',workspace:f.workspace,sessionDir:path.join(f.root,'session'),prompt,approvalMode:'allow',allowShell:false})+'\n'); if(e.event?.type==='model_request') child.stdin.write(JSON.stringify({type:'model_permission',requestId:e.event.requestId,approved:f.allowModel!==false})+'\n'); if(e.type==='response'&&e.id==='turn') child.stdin.end(); });
  const timer = setTimeout(() => child.kill(), 30000);
  const code = await new Promise(resolve => child.once('close',resolve)); clearTimeout(timer);
  assert.equal(code,0,stderr); assert.ok(events.some(x=>x.type==='response'&&x.id==='turn'),stdout);
  return {events,stderr,stdout,ok:events.findLast(x=>x.type==='response'&&x.id==='turn').ok};
}
function openai(res, delta, finish='stop') {
  res.writeHead(200,{'Content-Type':'text/event-stream'});
  for(const [d,f] of [[delta,null],[{},finish]]) res.write(`data: ${JSON.stringify({id:'mock',object:'chat.completion.chunk',created:1,model:'custom-model-v9',choices:[{index:0,delta:d,finish_reason:f}]})}\n\n`);
  res.end('data: [DONE]\n\n');
}
function anthropic(res, action) {
  res.writeHead(200,{'Content-Type':'text/event-stream'});
  const send=(type,data)=>res.write(`event: ${type}\ndata: ${JSON.stringify({type,...data})}\n\n`);
  send('message_start',{message:{id:'msg_mock',type:'message',role:'assistant',model:'custom-model-v9',content:[],stop_reason:null,stop_sequence:null,usage:{input_tokens:10,output_tokens:0}}});
  if(action) {
    send('content_block_start',{index:0,content_block:{type:'tool_use',id:'toolu_mock',name:'write',input:{}}});
    send('content_block_delta',{index:0,delta:{type:'input_json_delta',partial_json:JSON.stringify({path:'provider.txt',content:'Claude native tool result'})}});
  } else {
    send('content_block_start',{index:0,content_block:{type:'text',text:''}});
    send('content_block_delta',{index:0,delta:{type:'text_delta',text:'Native Claude stream finished.'}});
  }
  send('content_block_stop',{index:0});
  send('message_delta',{delta:{stop_reason:action?'tool_use':'end_turn',stop_sequence:null},usage:{output_tokens:8}});
  send('message_stop',{}); res.end();
}
test('native Anthropic Messages SDK tool loop, SSE and persisted conversation', {timeout:60000}, async t=>{
  const f=await fixture(t,(_request,res,n)=>anthropic(res,n===1));
  const first=await run(f,{CODING_MODEL_API:'anthropic-messages'});
  assert.equal(first.ok,true,first.stdout); assert.equal(await fs.readFile(path.join(f.workspace,'provider.txt'),'utf8'),'Claude native tool result');
  assert.ok(first.events.some(x=>x.event?.type==='text_delta'&&x.event.delta.includes('Native Claude')));
  assert.equal(f.requests.length,2); assert.equal(new URL(f.requests[0].url,f.url).pathname,'/v1/messages');
  assert.equal(f.requests[0].headers['x-api-key'],key); assert.equal(f.requests[0].headers.authorization,undefined);
  assert.equal(f.requests[0].body.model,'custom-model-v9');
  assert.ok(f.requests[1].body.messages.some(x=>x.role==='user'&&x.content.some?.(c=>c.type==='tool_result')));
  const second=await run(f,{CODING_MODEL_API:'anthropic-messages'},'What file did you create?'); assert.equal(second.ok,true,second.stdout);
  assert.ok(JSON.stringify(f.requests.at(-1).body.messages).includes('Please create provider.txt'));
});
test('OpenAI-compatible complete root, /v1 and custom prefix URLs are preserved', {timeout:60000}, async t=>{
  const f=await fixture(t,(_request,res)=>openai(res,{role:'assistant',content:'ok'}));
  for(const prefix of ['', '/v1', '/gateway/provider/deepseek/v1']) {
    const result=await run(f,{CODING_MODEL_BASE_URL:f.url+prefix}); assert.equal(result.ok,true,result.stdout);
    assert.equal(f.requests.at(-1).url,prefix+'/chat/completions'); assert.equal(f.requests.at(-1).body.model,'custom-model-v9');
    assert.equal(f.requests.at(-1).headers.authorization,'Bearer '+key);
    assert.equal(f.requests.at(-1).body.temperature,undefined);
  }
});
test('DeepSeek and Kimi reasoning_content survive tool-result replay without forced sampling', {timeout:60000}, async t=>{
  for(const provider of ['deepseek','kimi']) {
    const f=await fixture(t,(_request,res,n)=>n===1 ? openai(res,{role:'assistant',reasoning_content:'reasoning supplied by test provider',tool_calls:[{index:0,id:'call_mock',type:'function',function:{name:'write',arguments:JSON.stringify({path:'provider.txt',content:provider})}}]},'tool_calls') : openai(res,{role:'assistant',content:'done'}));
    const result=await run(f,{CODING_MODEL_PROVIDER:provider}); assert.equal(result.ok,true,result.stdout);
    const replay=f.requests[1].body.messages.find(x=>x.role==='assistant'&&x.tool_calls);
    assert.equal(replay.reasoning_content,'reasoning supplied by test provider'); assert.equal(replay.content,'');
    for(const r of f.requests) { assert.equal(r.body.temperature,undefined); assert.equal(r.body.thinking,undefined); assert.equal(r.body.reasoning_effort,undefined); assert.ok(r.body.max_tokens>0); }
  }
});
test('wrong credentials never leak echoed key into protocol, diagnostics or pi JSONL', {timeout:60000}, async t=>{
  for(const api of ['openai-completions','anthropic-messages']) {
    const f=await fixture(t,(_request,res)=>{ res.writeHead(401,{'Content-Type':'application/json'}); res.end(JSON.stringify({type:'error',error:{type:'authentication_error',message:'Invalid API key '+key}})); });
    const result=await run(f,{CODING_MODEL_API:api}); assert.equal(result.ok,false); assert.equal(f.requests.length,1);
    assert.ok(result.stdout.includes('[redacted]')); assert.ok(!result.stdout.includes(key)); assert.ok(!result.stderr.includes(key));
    for(const file of await fs.readdir(path.join(f.root,'session'))) { const content=await fs.readFile(path.join(f.root,'session',file),'utf8'); assert.ok(!content.includes(key),file); }
  }
});
test('redirects cannot forward BYOK credentials to another endpoint', {timeout:60000}, async t=>{
  const sink=await fixture(t,(_request,res)=>openai(res,{role:'assistant',content:'unexpected'}));
  const source=await fixture(t,(_request,res)=>{res.writeHead(307,{Location:sink.url+'/stolen'});res.end();});
  const result=await run(source); assert.equal(result.ok,false); assert.equal(source.requests.length,1); assert.equal(sink.requests.length,0); assert.ok(!result.stdout.includes(key));
});
test('private IPv4/IPv6 and DNS rebinding destinations fail before a connection', async t=>{
  for(const address of ['127.0.0.1','10.0.0.1','169.254.169.254','100.64.0.1','::1','::ffff:127.0.0.1','2001:db8::1','2002:7f00:1::']) assert.equal(isPublicAddress(address),false,address);
  assert.equal(isPublicAddress('8.8.8.8'),true); assert.equal(isPublicAddress('2606:4700:4700::1111'),true);
  const f=await fixture(t,(_request,res)=>{res.end('unexpected');});
  const direct=createModelTransport(f.url,{apiKey:key}); t.after(()=>direct.close()); await assert.rejects(direct.fetch(f.url),/public IP|HTTPS/); assert.equal(f.requests.length,0);
  let lookups=0;
  const rebound=createModelTransport('https://public.example',{apiKey:key,resolveHost:async()=>{lookups++;return [{address:'127.0.0.1',family:4}];}}); t.after(()=>rebound.close());
  await assert.rejects(rebound.fetch('https://public.example')); assert.equal(lookups,1); assert.equal(f.requests.length,0);
});
test('unknown API fails before model HTTP requests', async t=>{
  const f=await fixture(t,(_request,res)=>res.end()); const result=await run(f,{CODING_MODEL_API:'untrusted-api'});
  assert.equal(result.ok,false); assert.match(result.stdout,/Unsupported coding model API/); assert.equal(f.requests.length,0);
});



test('provider echoed secret split across SSE deltas is redacted before UI and session persistence', async t=>{
  const f=await fixture(t,(_request,res)=>{
    res.writeHead(200,{'Content-Type':'text/event-stream'});
    const parts=['Echo '+key.slice(0,9),key.slice(9)+' completed'];
    for(const content of parts) res.write(`data: ${JSON.stringify({id:'echo',object:'chat.completion.chunk',created:1,model:'custom-model-v9',choices:[{index:0,delta:{content},finish_reason:null}]})}\n\n`);
    res.write(`data: ${JSON.stringify({id:'echo',object:'chat.completion.chunk',created:1,model:'custom-model-v9',choices:[{index:0,delta:{},finish_reason:'stop'}]})}\n\n`);res.end('data: [DONE]\n\n');
  });
  const result=await run(f); assert.equal(result.ok,true,result.stdout);
  const displayed=result.events.filter(x=>x.event?.type==='text_delta').map(x=>x.event.delta).join('');
  assert.equal(displayed,'Echo [redacted] completed');
  for(const file of await fs.readdir(path.join(f.root,'session'))) assert.ok(!(await fs.readFile(path.join(f.root,'session',file),'utf8')).includes(key));
});

test('production JSONL admission denies HTTP and then allows one separately accounted request', {timeout:60000}, async t=>{
  const f=await fixture(t,(_request,res)=>openai(res,{role:'assistant',content:'approved'}));
  f.allowModel=false;const denied=await run(f,{CODING_GOVERNANCE_REQUIRED:'true'});
  assert.equal(denied.ok,false);assert.equal(f.requests.length,0);
  f.allowModel=true;const allowed=await run(f,{CODING_GOVERNANCE_REQUIRED:'true'});
  assert.equal(allowed.ok,true,allowed.stdout);assert.equal(f.requests.length,1);
  const admission=allowed.events.find(x=>x.event?.type==='model_request').event;
  const usage=allowed.events.find(x=>x.event?.type==='model_usage').event;
  assert.equal(usage.requestId,admission.requestId);assert.equal(usage.inputTokens,null);assert.equal(usage.outputTokens,null);assert.equal(usage.status,'SUCCEEDED');
});

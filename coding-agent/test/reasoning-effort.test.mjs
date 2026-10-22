import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import readline from 'node:readline';
import { spawn } from 'node:child_process';
import { createModelTransport, isPublicAddress } from '../src/model-transport.mjs';

import { createReasoningEffort } from '../src/reasoning-effort.mjs';

const key = 'offline-byok-secret-123';
async function fixture(t, responder) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'coding-provider-test-'));
  const workspace = path.join(root, 'workspace'); await fs.mkdir(workspace);
  const requests = [];
  const server = http.createServer(async (req,res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const request = { url: req.url, headers: req.headers, raw, body: raw ? JSON.parse(raw) : {} }; requests.push(request);
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

function assertBudget(f, result) {
  assert.equal(result.ok, true, result.stdout);
  assert.equal(f.requests.length, 1);
  const request = f.requests[0], cap = request.body.max_tokens ?? request.body.max_completion_tokens;
  assert.equal(cap, 97, 'effort must not increase governed output cap');
  assert.equal(result.events.find(x => x.event?.type === 'model_request').event.reservedTokens, Buffer.byteLength(request.raw) + cap, 'admission includes final native effort fields');
  assert.equal(result.events.filter(x => x.event?.type === 'model_usage').length, 1);
}
const env = (provider, effort) => ({ CODING_MODEL_PROVIDER: provider, CODING_MODEL_REASONING_EFFORT: effort, CODING_GOVERNANCE_REQUIRED: 'true', CODING_OUTPUT_TOKENS: '97', CODING_MODEL_API: provider === 'anthropic' ? 'anthropic-messages' : 'openai-completions' });

test('effort defaults leave SDK request unchanged; unknown providers/API/levels fail closed', async () => {
  const fetch = () => {};
  for (const effort of [undefined, '', 'default']) assert.equal(createReasoningEffort({ effort })(fetch), fetch);
  for (const input of [
    { provider: 'kimi', api: 'openai-completions', effort: 'high' },
    { provider: 'openai-compatible', api: 'openai-completions', effort: 'high' },
    { provider: 'openai', api: 'anthropic-messages', effort: 'high' },
    { provider: 'deepseek', api: 'openai-completions', effort: 'medium' },
    { provider: 'anthropic', api: 'anthropic-messages', effort: 'none' },
    { provider: 'openai', api: 'openai-completions', effort: 'unbounded' },
  ]) assert.throws(() => createReasoningEffort(input), /Unsupported reasoning effort/);
  const wrapped = createReasoningEffort({ provider: 'openai', api: 'openai-completions', effort: 'high' })(fetch);
  for (const body of [undefined, 'invalid', 'null', '[]']) await assert.rejects(wrapped('http://unused', { body }), /JSON|Invalid/);
});

test('OpenAI GPT-5 and o-series effort/cap survive SDK serialization and exact admission', { timeout: 60000 }, async t => {
  for (const [model, effort] of [['gpt-5', 'low'], ['gpt-5', 'medium'], ['gpt-5', 'high'], ['o3', 'high']]) {
    const f = await fixture(t, (_req, res) => openai(res, { role: 'assistant', content: 'done' }));
    const result = await run(f, { ...env('openai', effort), CODING_MODEL_NAME: model }); assertBudget(f, result);
    assert.equal(f.requests[0].body.reasoning_effort, effort); assert.equal(f.requests[0].body.model, model);
    assert.equal(f.requests[0].body.max_completion_tokens, 97); assert.equal(f.requests[0].body.max_tokens, undefined);
    assert.equal(f.requests[0].body.thinking, undefined); assert.equal(f.requests[0].body.temperature, undefined);
  }
});

test('DeepSeek native effort and explicit thinking-off preserve capped SDK requests', { timeout: 60000 }, async t => {
  for (const effort of ['low', 'high', 'max', 'none']) {
    const f = await fixture(t, (_req, res) => openai(res, { role: 'assistant', content: 'done' }));
    const result = await run(f, env('deepseek', effort)); assertBudget(f, result);
    assert.deepEqual(f.requests[0].body.thinking, { type: effort === 'none' ? 'disabled' : 'enabled' });
    assert.equal(f.requests[0].body.reasoning_effort, effort === 'none' ? undefined : effort);
    assert.equal(f.requests[0].body.temperature, undefined);
  }
});

test('Anthropic native output_config effort reaches Messages without inventing a thinking budget', { timeout: 60000 }, async t => {
  const f = await fixture(t, (_req, res) => anthropic(res, false));
  const result = await run(f, env('anthropic', 'high')); assertBudget(f, result);
  assert.deepEqual(f.requests[0].body.output_config, { effort: 'high' });
  assert.equal(f.requests[0].body.reasoning_effort, undefined); assert.equal(f.requests[0].body.thinking, undefined);
});

test('default effort on all provider SDKs preserves pre-existing payload behavior', { timeout: 60000 }, async t => {
  for (const provider of ['openai', 'deepseek', 'anthropic']) {
    const f = await fixture(t, (_req, res) => provider === 'anthropic' ? anthropic(res, false) : openai(res, { role: 'assistant', content: 'done' }));
    const result = await run(f, env(provider, 'default')); assertBudget(f, result);
    const body = f.requests[0].body;
    assert.equal(body.reasoning_effort, undefined); assert.equal(body.thinking, undefined); assert.equal(body.output_config, undefined);
  }
});

test('effort cannot bypass account admission; unsupported values never contact provider', { timeout: 60000 }, async t => {
  const f = await fixture(t, (_req, res) => openai(res, { role: 'assistant', content: 'done' }));
  f.allowModel = false;
  const denied = await run(f, env('openai', 'high'));
  assert.equal(denied.ok, false); assert.equal(f.requests.length, 0);
  f.allowModel = true;
  const invalid = await run(f, env('deepseek', 'medium'));
  assert.equal(invalid.ok, false); assert.match(invalid.stdout, /Unsupported reasoning effort/);
  assert.equal(invalid.events.filter(x => x.event?.type === 'model_request').length, 0); assert.equal(f.requests.length, 0);
});


test('DeepSeek effort survives a tool round trip together with reasoning replay and per-request governance', { timeout: 60000 }, async t => {
  const f = await fixture(t, (_request, res, n) => n === 1
    ? openai(res, { role: 'assistant', reasoning_content: 'provider thought', tool_calls: [{ index: 0, id: 'call_effort', type: 'function', function: { name: 'write', arguments: JSON.stringify({ path: 'provider.txt', content: 'verified effort' }) } }] }, 'tool_calls')
    : openai(res, { role: 'assistant', content: 'done' }));
  const result = await run(f, env('deepseek', 'high'));
  assert.equal(result.ok, true, result.stdout); assert.equal(f.requests.length, 2);
  assert.equal(await fs.readFile(path.join(f.workspace, 'provider.txt'), 'utf8'), 'verified effort');
  assert.equal(f.requests[1].body.messages.find(m => m.role === 'assistant' && m.tool_calls).reasoning_content, 'provider thought');
  const admissions = result.events.filter(x => x.event?.type === 'model_request');
  assert.equal(admissions.length, 2);
  for (const [i, request] of f.requests.entries()) {
    assert.equal(request.body.reasoning_effort, 'high'); assert.deepEqual(request.body.thinking, { type: 'enabled' });
    assert.equal(request.body.max_tokens, 97);
    assert.equal(admissions[i].event.reservedTokens, Buffer.byteLength(request.raw) + 97);
  }
});


test('Anthropic configured root and /v1 URLs preserve proxy prefix without duplicate version', { timeout: 60000 }, async t => {
  for (const prefix of ['', '/v1', '/gateway/v1', '/gateway/v1/']) {
    const f = await fixture(t, (_req, res) => anthropic(res, false));
    const result = await run(f, { ...env('anthropic', 'high'), CODING_MODEL_BASE_URL: f.url + prefix });
    assertBudget(f, result);
    assert.equal(new URL(f.requests[0].url, f.url).pathname, prefix.startsWith('/gateway') ? '/gateway/v1/messages' : '/v1/messages');
    assert.deepEqual(f.requests[0].body.output_config, { effort: 'high' });
  }
});

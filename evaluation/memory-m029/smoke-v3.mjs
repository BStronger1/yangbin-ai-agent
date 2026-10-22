import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { Budget, claimRun, grade, prepareRuntimeFixture, seedHistory, sha256, summarize } from './core.mjs';
import { offlineServer } from './smoke-v3-fixture.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const FROZEN = { 'smoke-v3-protocol.json': 'b9d8431c6417e5d2107797ebc9cc47a27011c48587ddac3ec692000fc9f4f960', 'smoke-v3-task.json': '52a7891dd294c74313f8cba063f01e94c33e56def2274432d3451d5a07b7b809' };

export function classifyFailure(error) {
  const message = String(error?.message ?? error ?? '');
  if (/generation hit the token cap and the summary is incomplete/.test(message)) return 'summary_output_cap';
  if (/Summarization attempted to call a tool/.test(message)) return 'summary_tool_call';
  if (/Actual compaction did not produce a summary/.test(message)) return 'empty_summary';
  if (/SDK output budget differs/.test(message)) return 'invalid_output_budget';
  return 'runtime_error_unclassified';
}

export function scoreComponents(task, answer, group) {
  let text=String(answer??'').trim(), value;
  const fence=text.match(/^```(?:json)?\s*\n([\s\S]*?)\n```$/i);
  if(fence)text=fence[1].trim();
  try { value=JSON.parse(text); } catch { return { responseFormatValid:false,shortTermCorrect:null,longTermCorrect:null,jointCorrect:null,appropriateAbsence:null,reason:'invalid-json' }; }
  const keys=Object.keys(task.expected);
  if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==keys.length||keys.some(k=>!Object.hasOwn(value,k)))return {responseFormatValid:false,shortTermCorrect:null,longTermCorrect:null,jointCorrect:null,appropriateAbsence:null,reason:'invalid-fields'};
  const shortTermCorrect=value.batchStride===task.expected.batchStride;
  const longTermCorrect=value.archiveCodec===task.expected.archiveCodec;
  const appropriateAbsence=['A','B'].includes(group)&&value.archiveCodec===null;
  return {responseFormatValid:true,shortTermCorrect,longTermCorrect,jointCorrect:shortTermCorrect&&longTermCorrect,appropriateAbsence,shortTermOutcome:shortTermCorrect?'correct':'not-recovered',longTermOutcome:longTermCorrect?'correct':appropriateAbsence?'unavailable-abstained':'incorrect-or-not-recovered',reason:'component-scoring'};
}

function observeSse(response, record, redact) {
  record.httpStatus=response.status; record.finishReasons=[]; record.visibleText=''; record.thinkingChars=0; record.toolCallChunks=0; record.inputTokens=null; record.outputTokens=null; record.reasoningTokens=null;
  if(!response.body)return response;
  let buffer='',decoder=new TextDecoder();
  const line=raw=>{
    if(!raw.startsWith('data:'))return;
    const payload=raw.slice(5).trim(); if(!payload||payload==='[DONE]')return;
    let item;try{item=JSON.parse(payload);}catch{return;}
    const usage=item.usage;if(usage){if(Number.isSafeInteger(usage.prompt_tokens)&&usage.prompt_tokens>=0)record.inputTokens=usage.prompt_tokens;if(Number.isSafeInteger(usage.completion_tokens)&&usage.completion_tokens>=0)record.outputTokens=usage.completion_tokens;const reasoning=usage.completion_tokens_details?.reasoning_tokens;if(Number.isSafeInteger(reasoning)&&reasoning>=0)record.reasoningTokens=reasoning;}
    for(const choice of item.choices||[]){
      if(choice.finish_reason){const reason=['stop','length','tool_calls','function_call','content_filter'].includes(choice.finish_reason)?choice.finish_reason:'other';if(!record.finishReasons.includes(reason))record.finishReasons.push(reason);}
      const delta=choice.delta||{};
      if(typeof delta.content==='string')record.visibleText=(record.visibleText+delta.content).slice(0,64000);
      for(const key of ['reasoning_content','reasoning','reasoning_text'])if(typeof delta[key]==='string')record.thinkingChars+=delta[key].length;
      if(delta.tool_calls)record.toolCallChunks+=delta.tool_calls.length;
    }
  };
  const transform=new TransformStream({
    transform(chunk,controller){buffer+=decoder.decode(chunk,{stream:true});let index;while((index=buffer.indexOf('\n'))>=0){line(buffer.slice(0,index).replace(/\r$/,''));buffer=buffer.slice(index+1);}if(buffer.length>1048576){buffer='';record.observationTruncated=true;}controller.enqueue(chunk);},
    flush(){buffer+=decoder.decode();if(buffer)line(buffer);record.visibleText=redact(record.visibleText);},
  });
  return new Response(response.body.pipeThrough(transform),{status:response.status,statusText:response.statusText,headers:response.headers});
}

export async function executeSmokeV3({ mode, runDirectory, runtimeDir = path.resolve(here, '../../coding-agent'), env = process.env, testResponse } = {}) {
  if (!['offline', 'real'].includes(mode) || !runDirectory || !path.isAbsolute(runDirectory)) throw new Error('Explicit mode and absolute isolated directory required');
  if (mode === 'real' && testResponse) throw new Error('Test responder is unavailable in real mode');
  const inputs = {};
  for (const [name, expected] of Object.entries(FROZEN)) {
    const bytes = await fs.readFile(path.join(here, name));
    if (sha256(bytes) !== expected) throw new Error('Frozen smoke input changed');
    inputs[name] = JSON.parse(bytes);
  }
  const protocol = inputs['smoke-v3-protocol.json'], task = inputs['smoke-v3-task.json'];
  const importAt = relative => import(pathToFileURL(path.join(runtimeDir, relative)).href);
  const { runAgent } = await importAt('src/agent.mjs');
  const { ModelGate } = await importAt('src/model-gate.mjs');
  const { createRedactor } = await importAt('src/model-transport.mjs');
  const redact = createRedactor(mode === 'real' ? env.CODING_MODEL_API_KEY || '' : 'offline-loopback').text;
  const { SessionManager } = await importAt('node_modules/@earendil-works/pi-coding-agent/dist/index.js');
  const { clampMaxTokensToContext } = await importAt('node_modules/@earendil-works/pi-ai/dist/api/simple-options.js');
  if (clampMaxTokensToContext({ contextWindow: protocol.contextWindow }, { messages: [] }, protocol.budget.maxOutputTokens) !== 2048) throw new Error('Invalid static SDK output capacity');
  const model = env.CODING_MODEL_NAME || env.CODING_MODEL_MODEL;
  if (mode === 'real' && (!env.CODING_MODEL_API_KEY || !env.CODING_MODEL_BASE_URL || model !== protocol.model.model || (env.CODING_MODEL_PROVIDER && env.CODING_MODEL_PROVIDER !== 'custom') || (env.CODING_MODEL_API && env.CODING_MODEL_API !== 'openai-completions') || env.CODING_MODEL_REASONING_EFFORT)) throw new Error('Frozen model settings mismatch');
  const sources = {};
  for (const name of (await fs.readdir(path.join(runtimeDir, 'src'))).filter(n => n.endsWith('.mjs')).sort()) sources['src/' + name] = sha256(await fs.readFile(path.join(runtimeDir, 'src', name)));
  for (const name of ['package.json','package-lock.json']) sources[name] = sha256(await fs.readFile(path.join(runtimeDir,name)));
  const evaluatorHashes = {};
  for (const name of ['core.mjs','smoke-v3-fixture.mjs','smoke-v3.mjs']) evaluatorHashes[name] = sha256(await fs.readFile(path.join(here,name)));
  const manifest = { id: protocol.id, mode, startedAt: new Date().toISOString(), frozenInputs: FROZEN, baselineManifestSha256: protocol.baselineManifestSha256, sources, evaluatorHashes, model: protocol.model, endpointSha256: mode === 'real' ? sha256(env.CODING_MODEL_BASE_URL) : null, budget: protocol.budget, priorBatches: protocol.priorBatches, qualityScope: 'n=1 feasibility only; no generalization; all groups forcibly compact through actual pi SDK before answering' };
  await claimRun(runDirectory, manifest);
  await fs.writeFile(path.join(runDirectory, 'manifest.json'), JSON.stringify(manifest, null, 2));
  let offline;
  if (mode === 'offline') offline = await offlineServer(testResponse);
  const budget = new Budget(protocol.budget), results = [], requestMetadata = [];
  let current, halt = false;
  const originalAdmit = ModelGate.prototype.admit, originalWrap = ModelGate.prototype.wrap;
  ModelGate.prototype.wrap = function(fetch) {
    const observedFetch = async (input, init) => { const record = requestMetadata.at(-1); const response = await fetch(input, init); return observeSse(response, record, redact); };
    return originalWrap.call(this, observedFetch);
  };
  // Isolated evaluator process only: observe and reject inadequate output ceilings before
  // the production gate/transport. No request rewriting, no mocked retrieval/agent loop.
  ModelGate.prototype.admit = async function(init) {
    const body = JSON.parse(init.body);
    const actualMaxOutputTokens = Number(body.max_tokens ?? body.max_completion_tokens);
    requestMetadata.push({ group: current.group, actualMaxOutputTokens, requestBytes: Buffer.byteLength(init.body), summary: (body.messages || []).some(message => ['system','developer'].includes(message.role) && JSON.stringify(message.content).includes('You are a context summarization assistant.')) });
    if (actualMaxOutputTokens !== protocol.budget.maxOutputTokens) {
      current.invalidOutputBudget = true;
      throw new Error('SDK output budget differs from frozen 2048');
    }
    return originalAdmit.call(this, init);
  };
  try {
    for (const group of protocol.executionOrder) {
      current = { group, invalidOutputBudget: false };
      if (halt) {
        const row = { taskId: task.id, group, target: task.target, status: 'not_run_preflight_failure', quality: null, requests: 0, reservedTokens: 0, inputTokens: 0, outputTokens: 0, unknownUsageRequests: 0, latencyMs: 0, compactions: 0 };
        results.push(row); await fs.appendFile(path.join(runDirectory, 'results.jsonl'), JSON.stringify(row) + '\n'); continue;
      }
      const fixture = await prepareRuntimeFixture(runDirectory, task, group);
      await seedHistory(SessionManager, fixture, task.sameSessionHistory);
      const instance = budget.instance(), usage = new Map(), events = [];
      let gate, tools, answer = '', status = 'completed', compactedResult = false, failureCategory = null;
      const started = performance.now(), abort = new AbortController();
      const timer = setTimeout(() => abort.abort(), protocol.budget.maxTaskSeconds * 1000);
      try {
        await runAgent({ ...fixture, prompt: task.prompt, approvalMode: 'ask', allowShell: false }, {
          signal: abort.signal, onGate: value => { gate = value; }, onTools: value => { tools = value; },
          onSession: session => {
            const prompt = session.prompt.bind(session); let once = false;
            session.prompt = async (...args) => {
              if (!once) { once = true; const result = await session.compact(); compactedResult = !!result?.summary; if (!compactedResult) throw new Error('Actual compaction did not produce a summary'); }
              return prompt(...args);
            };
          },
          emit: event => {
            if (event.type === 'model_request') gate.permission({ requestId: event.requestId, approved: budget.admit(instance, event.reservedTokens) });
            else if (event.type === 'model_usage') usage.set(event.requestId, event);
            else if (event.type === 'text_delta') answer += event.delta;
            else if (event.type === 'approval_required') tools.approve(event.approvalId, false);
            else if (event.type === 'context') events.push(event);
          },
        }, {
          CODING_MODEL_API_KEY: mode === 'real' ? env.CODING_MODEL_API_KEY : 'offline-loopback',
          CODING_MODEL_BASE_URL: mode === 'real' ? env.CODING_MODEL_BASE_URL : offline.url,
          CODING_MODEL_MODEL: mode === 'real' ? model : 'offline', CODING_MODEL_PROVIDER: 'custom',
          CODING_MODEL_API: 'openai-completions', CODING_MODEL_REASONING_EFFORT: '',
          CODING_MODEL_ALLOW_PRIVATE: mode === 'offline' ? 'true' : (env.CODING_MODEL_ALLOW_PRIVATE === 'true' ? 'true' : 'false'),
          CODING_GOVERNANCE_REQUIRED: 'true', CODING_CONTEXT_WINDOW: String(protocol.contextWindow), CODING_OUTPUT_TOKENS: '2048',
          CODING_CONTEXT_POLICY: protocol.groups[group].shortTerm, CODING_LONG_TERM_MEMORY: protocol.groups[group].longTerm ? 'on' : 'off',
          CODING_MEMORY_MAX_BYTES: '1024', CODING_MEMORY_LIMIT: '4',
        });
      } catch (error) {
        failureCategory = classifyFailure(error);
        status = current.invalidOutputBudget ? 'invalid_output_budget' : instance.denied ? 'budget_exhausted' : abort.signal.aborted ? 'timeout' : 'runtime_error';
      } finally { clearTimeout(timer); }
      const rows = [...usage.values()], compactions = events.filter(event => event.status === 'COMPACTED').length;
      if (status === 'completed' && (!compactedResult || compactions !== 1)) status = 'invalid_compaction';
      if (status === 'completed' && rows.some(row => row.status !== 'SUCCEEDED')) status = 'provider_response_unknown';
      if (status === 'completed' && !answer.trim()) status = 'invalid_empty_response';
      if (status !== 'completed') halt = true;
      const componentScore = mode === 'real' && status === 'completed' ? scoreComponents(task, answer, group) : null;
      const scored = componentScore?.responseFormatValid ? { pass: componentScore.jointCorrect, reason: 'joint-criterion-not-general-quality' } : null;
      const result = { taskId: task.id, group, target: task.target, status, failureCategory, componentScore, quality: scored?.pass ?? null, grade: scored, answer, requests: instance.requests, reservedTokens: instance.reservedTokens, budgetDenial: instance.denied, inputTokens: mode === 'real' ? rows.reduce((n,row) => n+(row.inputTokens??0),0) : 0, outputTokens: mode === 'real' ? rows.reduce((n,row) => n+(row.outputTokens??0),0) : 0, unknownUsageRequests: mode === 'real' ? instance.requests-rows.filter(row => row.inputTokens !== null && row.outputTokens !== null).length : 0, usageIsSynthetic: mode === 'offline', latencyMs: Math.round(performance.now()-started), compactions, forcedSdkCompaction: true, memoryEvents: events.filter(event => event.status === 'MEMORY_RETRIEVED'), fees: 'UNKNOWN' };
      results.push(result); await fs.appendFile(path.join(runDirectory, 'results.jsonl'), JSON.stringify(result) + '\n');
    }
    for (const record of requestMetadata) record.visibleText = redact(record.visibleText || '');
    const summary = summarize(results, protocol, mode);
    summary.components = results.map(row => ({ group: row.group, status: row.status, componentScore: row.componentScore ?? null }));
    summary.qualityScope = manifest.qualityScope;
    summary.budget = { requests: budget.requests, reservedTokens: budget.reservedTokens, limits: protocol.budget };
    summary.actualMaxOutputTokens = [...new Set(requestMetadata.map(row => row.actualMaxOutputTokens))];
    summary.completedAt = new Date().toISOString();
    await fs.writeFile(path.join(runDirectory, 'summary.json'), JSON.stringify(summary,null,2));
    await fs.writeFile(path.join(runDirectory, 'request-metadata.json'), JSON.stringify(requestMetadata,null,2));
    await fs.writeFile(path.join(runDirectory, 'COMPLETED'), summary.completedAt);
    return { summary, results, requestMetadata, offlineRequests: offline?.requests };
  } finally { ModelGate.prototype.admit = originalAdmit; ModelGate.prototype.wrap = originalWrap; if (offline) await offline.close(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2), value = name => { const at=args.indexOf(name); return at<0 ? undefined : args[at+1]; };
  try { const { summary } = await executeSmokeV3({ mode: args.includes('--real') ? 'real' : args.includes('--offline') ? 'offline' : undefined, runDirectory: value('--run-dir'), runtimeDir: value('--runtime-dir') }); console.log(JSON.stringify(summary,null,2)); }
  catch(error) { console.error(JSON.stringify({ status: 'smoke-stopped', reason: error.code === 'EEXIST' ? 'STARTED exists; no replay' : 'Frozen input/configuration or preflight failed; no automatic retry' })); process.exitCode=1; }
}

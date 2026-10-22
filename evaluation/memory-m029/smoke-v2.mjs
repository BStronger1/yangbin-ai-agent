import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { Budget, claimRun, grade, prepareRuntimeFixture, seedHistory, sha256, summarize } from './core.mjs';
import { offlineServer } from './run.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const FROZEN = { 'smoke-v2-protocol.json': 'bf226891f035fd5ba31dad8b713009bce04e8964457fccf4a9cebf6d34013637', 'smoke-v2-task.json': 'a9209a3a9dbcec7bb80f147e9c0b91bebf6f0f3541dfe1693b2fe8838775c7c7' };

export function classifyFailure(error) {
  const message = String(error?.message ?? error ?? '');
  if (/generation hit the token cap and the summary is incomplete/.test(message)) return 'summary_output_cap';
  if (/Summarization attempted to call a tool/.test(message)) return 'summary_tool_call';
  if (/Actual compaction did not produce a summary/.test(message)) return 'empty_summary';
  if (/SDK output budget differs/.test(message)) return 'invalid_output_budget';
  return 'runtime_error_unclassified';
}
export async function executeSmoke({ mode, runDirectory, runtimeDir = path.resolve(here, '../../coding-agent'), env = process.env, testResponse } = {}) {
  if (!['offline', 'real'].includes(mode) || !runDirectory || !path.isAbsolute(runDirectory)) throw new Error('Explicit mode and absolute isolated directory required');
  if (mode === 'real' && testResponse) throw new Error('Test responder is unavailable in real mode');
  const inputs = {};
  for (const [name, expected] of Object.entries(FROZEN)) {
    const bytes = await fs.readFile(path.join(here, name));
    if (sha256(bytes) !== expected) throw new Error('Frozen smoke input changed');
    inputs[name] = JSON.parse(bytes);
  }
  const protocol = inputs['smoke-v2-protocol.json'], task = inputs['smoke-v2-task.json'];
  const importAt = relative => import(pathToFileURL(path.join(runtimeDir, relative)).href);
  const { runAgent } = await importAt('src/agent.mjs');
  const { ModelGate } = await importAt('src/model-gate.mjs');
  const { SessionManager } = await importAt('node_modules/@earendil-works/pi-coding-agent/dist/index.js');
  const { clampMaxTokensToContext } = await importAt('node_modules/@earendil-works/pi-ai/dist/api/simple-options.js');
  if (clampMaxTokensToContext({ contextWindow: protocol.contextWindow }, { messages: [] }, protocol.budget.maxOutputTokens) !== 512) throw new Error('Invalid static SDK output capacity');
  const model = env.CODING_MODEL_NAME || env.CODING_MODEL_MODEL;
  if (mode === 'real' && (!env.CODING_MODEL_API_KEY || !env.CODING_MODEL_BASE_URL || model !== protocol.model.model || (env.CODING_MODEL_PROVIDER && env.CODING_MODEL_PROVIDER !== 'custom') || (env.CODING_MODEL_API && env.CODING_MODEL_API !== 'openai-completions') || env.CODING_MODEL_REASONING_EFFORT)) throw new Error('Frozen model settings mismatch');
  const sources = {};
  for (const name of (await fs.readdir(path.join(runtimeDir, 'src'))).filter(n => n.endsWith('.mjs')).sort()) sources['src/' + name] = sha256(await fs.readFile(path.join(runtimeDir, 'src', name)));
  for (const name of ['package.json','package-lock.json']) sources[name] = sha256(await fs.readFile(path.join(runtimeDir,name)));
  const evaluatorHashes = {};
  for (const name of ['core.mjs','run.mjs','smoke-v2.mjs']) evaluatorHashes[name] = sha256(await fs.readFile(path.join(here,name)));
  const manifest = { id: protocol.id, mode, startedAt: new Date().toISOString(), frozenInputs: FROZEN, baselineManifestSha256: protocol.baselineManifestSha256, sources, evaluatorHashes, model: protocol.model, endpointSha256: mode === 'real' ? sha256(env.CODING_MODEL_BASE_URL) : null, budget: protocol.budget, combinedWithV1: protocol.combinedWithV1, qualityScope: 'n=1 feasibility only; no generalization; all groups forcibly compact through actual pi SDK before answering' };
  await claimRun(runDirectory, manifest);
  await fs.writeFile(path.join(runDirectory, 'manifest.json'), JSON.stringify(manifest, null, 2));
  let offline;
  if (mode === 'offline') offline = await offlineServer(testResponse);
  const budget = new Budget(protocol.budget), results = [], requestMetadata = [];
  let current, halt = false;
  const originalAdmit = ModelGate.prototype.admit;
  // Isolated evaluator process only: observe and reject inadequate output ceilings before
  // the production gate/transport. No request rewriting, no mocked retrieval/agent loop.
  ModelGate.prototype.admit = async function(init) {
    const body = JSON.parse(init.body);
    const actualMaxOutputTokens = Number(body.max_tokens ?? body.max_completion_tokens);
    requestMetadata.push({ group: current.group, actualMaxOutputTokens, requestBytes: Buffer.byteLength(init.body), summary: (body.messages || []).some(message => ['system','developer'].includes(message.role) && JSON.stringify(message.content).includes('You are a context summarization assistant.')) });
    if (actualMaxOutputTokens !== protocol.budget.maxOutputTokens) {
      current.invalidOutputBudget = true;
      throw new Error('SDK output budget differs from frozen 512');
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
          CODING_GOVERNANCE_REQUIRED: 'true', CODING_CONTEXT_WINDOW: String(protocol.contextWindow), CODING_OUTPUT_TOKENS: '512',
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
      const scored = mode === 'real' && status === 'completed' ? grade(task, answer) : null;
      const result = { taskId: task.id, group, target: task.target, status, failureCategory, quality: scored?.pass ?? null, grade: scored, answer, requests: instance.requests, reservedTokens: instance.reservedTokens, budgetDenial: instance.denied, inputTokens: mode === 'real' ? rows.reduce((n,row) => n+(row.inputTokens??0),0) : 0, outputTokens: mode === 'real' ? rows.reduce((n,row) => n+(row.outputTokens??0),0) : 0, unknownUsageRequests: mode === 'real' ? instance.requests-rows.filter(row => row.inputTokens !== null && row.outputTokens !== null).length : 0, usageIsSynthetic: mode === 'offline', latencyMs: Math.round(performance.now()-started), compactions, forcedSdkCompaction: true, memoryEvents: events.filter(event => event.status === 'MEMORY_RETRIEVED'), fees: 'UNKNOWN' };
      results.push(result); await fs.appendFile(path.join(runDirectory, 'results.jsonl'), JSON.stringify(result) + '\n');
    }
    const summary = summarize(results, protocol, mode);
    summary.qualityScope = manifest.qualityScope;
    summary.budget = { requests: budget.requests, reservedTokens: budget.reservedTokens, limits: protocol.budget };
    summary.actualMaxOutputTokens = [...new Set(requestMetadata.map(row => row.actualMaxOutputTokens))];
    summary.completedAt = new Date().toISOString();
    await fs.writeFile(path.join(runDirectory, 'summary.json'), JSON.stringify(summary,null,2));
    await fs.writeFile(path.join(runDirectory, 'request-metadata.json'), JSON.stringify(requestMetadata,null,2));
    await fs.writeFile(path.join(runDirectory, 'COMPLETED'), summary.completedAt);
    return { summary, results, requestMetadata, offlineRequests: offline?.requests };
  } finally { ModelGate.prototype.admit = originalAdmit; if (offline) await offline.close(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2), value = name => { const at=args.indexOf(name); return at<0 ? undefined : args[at+1]; };
  try { const { summary } = await executeSmoke({ mode: args.includes('--real') ? 'real' : args.includes('--offline') ? 'offline' : undefined, runDirectory: value('--run-dir'), runtimeDir: value('--runtime-dir') }); console.log(JSON.stringify(summary,null,2)); }
  catch(error) { console.error(JSON.stringify({ status: 'smoke-stopped', reason: error.code === 'EEXIST' ? 'STARTED exists; no replay' : 'Frozen input/configuration or preflight failed; no automatic retry' })); process.exitCode=1; }
}

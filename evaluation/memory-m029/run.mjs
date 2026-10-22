import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Budget, claimRun, grade, loadFrozen, prepareRuntimeFixture, seedHistory, sha256, summarize } from './core.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));

export async function offlineServer(respond) {
  const requests = [];
  const server = http.createServer(async (request, response) => {
    let raw = '';
    for await (const part of request) raw += part;
    const body = JSON.parse(raw);
    const summary = body.messages.some(m => ['system', 'developer'].includes(m.role) && JSON.stringify(m.content).includes('You are a context summarization assistant.'));
    requests.push({ body, bytes: Buffer.byteLength(raw), summary });
    const answer = respond ? await respond(body, { summary, index: requests.length }) : summary ? '## Progress\nRoutine work complete.\n## Next\nFollow the latest task.' : '{"offline":true}';
    response.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const chunk = (delta, finish_reason = null) => ({ id: 'offline-' + requests.length, object: 'chat.completion.chunk', model: 'offline', choices: [{ index: 0, delta, finish_reason }] });
    response.write('data: ' + JSON.stringify(chunk({ role: 'assistant', content: answer })) + '\n\n');
    response.write('data: ' + JSON.stringify({ ...chunk({}, 'stop'), usage: { prompt_tokens: Math.ceil(Buffer.byteLength(raw) / 4), completion_tokens: 20 } }) + '\n\n');
    response.end('data: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return { url: 'http://127.0.0.1:' + server.address().port + '/v1', requests, close: async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } };
}

async function runtimeHashes(runtimeDir) {
  const files = ['package.json', 'package-lock.json', ...(await fs.readdir(path.join(runtimeDir, 'src'))).filter(name => name.endsWith('.mjs')).sort().map(name => 'src/' + name)];
  const hashes = {};
  for (const name of files) hashes[name] = sha256(await fs.readFile(path.join(runtimeDir, name)));
  return hashes;
}

export async function execute({ mode, runDirectory, runtimeDir = path.resolve(directory, '../../coding-agent'), env = process.env, onRequestBodies } = {}) {
  if (!['offline', 'real'].includes(mode)) throw new Error('Explicit offline or real mode required');
  if (!runDirectory || !path.isAbsolute(runDirectory)) throw new Error('Absolute isolated run directory required');
  const { protocol, tasks, pressure, hashes } = await loadFrozen(directory);
  const { runAgent } = await import(pathToFileURL(path.join(runtimeDir, 'src', 'agent.mjs')).href);
  const { SessionManager } = await import(pathToFileURL(path.join(runtimeDir, 'node_modules', '@earendil-works', 'pi-coding-agent', 'dist', 'index.js')).href);
  const sources = await runtimeHashes(runtimeDir);
  if (mode === 'real') {
    const { clampMaxTokensToContext } = await import(pathToFileURL(path.join(runtimeDir, 'node_modules', '@earendil-works', 'pi-ai', 'dist', 'api', 'simple-options.js')).href);
    if (clampMaxTokensToContext({ contextWindow: protocol.contextWindow }, { messages: [] }, protocol.budget.maxOutputTokens) < protocol.budget.maxOutputTokens) { const error = new Error('Frozen protocol cannot provide the declared output budget under the pinned SDK'); error.code = 'INVALID_OUTPUT_BUDGET'; throw error; }
  }
  if (!sources['src/long-term-memory.mjs']) throw new Error('Production long-term runtime not installed');
  const model = env.CODING_MODEL_NAME || env.CODING_MODEL_MODEL;
  if (mode === 'real') {
    if (!env.CODING_MODEL_API_KEY || !env.CODING_MODEL_BASE_URL) throw new Error('Missing model credentials');
    if (model !== protocol.model.model) throw new Error('Frozen model mismatch');
    if (env.CODING_MODEL_API && env.CODING_MODEL_API !== protocol.model.api) throw new Error('Frozen API mismatch');
    if (env.CODING_MODEL_PROVIDER && env.CODING_MODEL_PROVIDER !== protocol.model.provider) throw new Error('Frozen provider mismatch');
    if (env.CODING_MODEL_REASONING_EFFORT) throw new Error('Frozen default reasoning requires empty effort');
  }
  const snapshot = { model: mode === 'real' ? model : 'offline', api: protocol.model.api, provider: protocol.model.provider, effort: 'default', endpointSha256: mode === 'real' ? sha256(env.CODING_MODEL_BASE_URL) : null };
  const evaluatorHashes = {}; for (const name of ['core.mjs', 'run.mjs']) evaluatorHashes[name] = sha256(await fs.readFile(path.join(directory, name)));
  const metadata = { evaluatorHashes, memoryPolicy: { maxBytes: protocol.memoryBudgetBytes, limit: protocol.memoryLimit, effectiveBytes: Math.max(256, Math.min(2048, protocol.contextWindow / 4, protocol.memoryBudgetBytes)) }, id: protocol.id, mode, startedAt: new Date().toISOString(), hashes, sources, model: snapshot, budget: protocol.budget, fees: 'UNKNOWN', syntheticFixturePreparation: true };
  await claimRun(runDirectory, metadata);
  await fs.writeFile(path.join(runDirectory, 'manifest.json'), JSON.stringify(metadata, null, 2));
  let offline;
  if (mode === 'offline') offline = await offlineServer();
  const budget = new Budget(protocol.budget), results = []; let providerHalted = false;
  try {
    for (const taskId of protocol.taskOrder) {
      const task = tasks.find(value => value.id === taskId);
      for (const group of protocol.executionOrder) {
        if (providerHalted) {
          const result = { taskId, group, category: task.category, target: task.target, status: 'not_run_provider_failure', quality: null, grade: null, answer: '', requests: 0, reservedTokens: 0, budgetDenial: null, inputTokens: 0, outputTokens: 0, unknownUsageRequests: 0, usageIsSynthetic: false, latencyMs: 0, compactions: 0, shortTermAttributionEligible: false, memoryEvents: [], fees: 'UNKNOWN' };
          results.push(result); await fs.appendFile(path.join(runDirectory, 'results.jsonl'), JSON.stringify(result) + '\n'); continue;
        }
        const fixture = await prepareRuntimeFixture(runDirectory, task, group);
        await seedHistory(SessionManager, fixture, pressure.history[task.id] || task.sameSessionHistory);
        const account = budget.instance(), started = performance.now(), events = [], usage = new Map();
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), protocol.budget.maxTaskSeconds * 1000);
        let gate, activeTools, answer = '', status = 'completed';
        try {
          await runAgent({ ...fixture, prompt: task.prompt, approvalMode: 'ask', allowShell: false }, {
            signal: controller.signal, onTools: tools => { activeTools = tools; },
            onGate: value => { gate = value; },
            emit: event => {
              if (event.type === 'model_request') gate.permission({ requestId: event.requestId, approved: budget.admit(account, event.reservedTokens) });
              else if (event.type === 'approval_required') activeTools.approve(event.approvalId, false);
              else if (event.type === 'model_usage') usage.set(event.requestId, { inputTokens: event.inputTokens, outputTokens: event.outputTokens, status: event.status });
              else if (event.type === 'text_delta') answer += event.delta;
              else if (['context', 'memory'].includes(event.type)) events.push(event);
              // Do not send approvals; no prompt or memory can grant permission.
            },
          }, {
            CODING_MODEL_API_KEY: mode === 'real' ? env.CODING_MODEL_API_KEY : 'offline-loopback-only',
            CODING_MODEL_BASE_URL: mode === 'real' ? env.CODING_MODEL_BASE_URL : offline.url,
            CODING_MODEL_MODEL: snapshot.model, CODING_MODEL_PROVIDER: snapshot.provider,
            CODING_MODEL_API: snapshot.api, CODING_MODEL_REASONING_EFFORT: '',
            CODING_MODEL_ALLOW_PRIVATE: mode === 'offline' ? 'true' : (env.CODING_MODEL_ALLOW_PRIVATE === 'true' ? 'true' : 'false'),
            CODING_GOVERNANCE_REQUIRED: 'true', CODING_CONTEXT_WINDOW: String(protocol.contextWindow),
            CODING_OUTPUT_TOKENS: String(protocol.budget.maxOutputTokens),
            CODING_CONTEXT_POLICY: protocol.groups[group].shortTerm,
            CODING_LONG_TERM_MEMORY: protocol.groups[group].longTerm ? 'on' : 'off',
            CODING_MEMORY_MAX_BYTES: String(protocol.memoryBudgetBytes), CODING_MEMORY_LIMIT: String(protocol.memoryLimit),
          });
        } catch {
          status = account.denied ? 'budget_exhausted' : controller.signal.aborted ? 'timeout' : 'runtime_error';
        } finally { clearTimeout(timeout); }
        const usageRows = [...usage.values()];
        if (mode === 'real' && status === 'completed' && usageRows.some(row => row.status !== 'SUCCEEDED')) status = 'provider_response_unknown';
        if (mode === 'real' && status === 'completed' && !answer.trim()) status = 'invalid_empty_response';
        if (mode === 'real' && ['runtime_error', 'timeout', 'provider_response_unknown', 'invalid_empty_response'].includes(status)) providerHalted = true;
        const compactions = events.filter(event => event.status === 'COMPACTED').length;
        const quality = status === 'completed' && mode === 'real' ? grade(task, answer) : null;
        const result = {
          taskId, group, category: task.category, target: task.target, status,
          quality: quality?.pass ?? null, grade: quality, answer,
          requests: account.requests, reservedTokens: account.reservedTokens, budgetDenial: account.denied,
          inputTokens: mode === 'real' ? usageRows.reduce((n,row) => n + (row.inputTokens ?? 0),0) : 0,
          outputTokens: mode === 'real' ? usageRows.reduce((n,row) => n + (row.outputTokens ?? 0),0) : 0,
          unknownUsageRequests: mode === 'real' ? account.requests - usageRows.filter(row => row.inputTokens !== null && row.outputTokens !== null).length : 0,
          usageIsSynthetic: mode === 'offline', latencyMs: Math.round(performance.now() - started),
          compactions, shortTermAttributionEligible: !!task.requiresCompactionForShortTermClaim && compactions > 0 && status === 'completed',
          memoryEvents: events.filter(event => event.type === 'memory' || String(event.policy ?? '').includes('memory')),
          fees: 'UNKNOWN',
        };
        results.push(result);
        await fs.appendFile(path.join(runDirectory, 'results.jsonl'), JSON.stringify(result) + '\n');
      }
    }
    const summary = summarize(results, protocol, mode);
    summary.budget = { requests: budget.requests, reservedTokens: budget.reservedTokens, limits: protocol.budget };
    summary.completedAt = new Date().toISOString();
    await fs.writeFile(path.join(runDirectory, 'summary.json'), JSON.stringify(summary, null, 2));
    await fs.writeFile(path.join(runDirectory, 'COMPLETED'), JSON.stringify({ completedAt: summary.completedAt, results: results.length }));
    if (offline) onRequestBodies?.(offline.requests);
    return { summary, results, offlineRequests: offline?.requests };
  } finally { if (offline) await offline.close(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2), value = name => { const index = args.indexOf(name); return index < 0 ? undefined : args[index + 1]; };
  try {
    const mode = args.includes('--real') ? 'real' : args.includes('--offline') ? 'offline' : undefined;
    const result = await execute({ mode, runDirectory: value('--run-dir'), runtimeDir: value('--runtime-dir') });
    console.log(JSON.stringify(result.summary, null, 2));
  } catch (error) {
    // Never print raw provider errors, URLs or environment values.
    console.error(JSON.stringify({ status: 'failed-before-or-during-run', error: error?.code === 'EEXIST' ? 'STARTED already exists; automatic replay refused' : 'Check non-secret configuration and run artifacts; no automatic retry' }));
    process.exitCode = 1;
  }
}

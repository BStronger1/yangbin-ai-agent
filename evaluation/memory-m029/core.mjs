import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash, randomUUID } from 'node:crypto';

export const sha256 = value => createHash('sha256').update(value).digest('hex');
export const FIXED_HASHES = {
  'protocol.json': '56c13e8528484fb3017294eb45360cb9ef51366f09767e2938f41a5299b79df1',
  'tasks.json': '43e5c7d657a29491706cc32f3cb4c4797c305691e092880a7e183cb35a5c2b2c',
  'pressure-history.json': '687ddc3c7adc5bcc65482d7a799dd1593fbc892378eed411b919ba822c8d54ae',
};

export async function loadFrozen(directory) {
  const parsed = {}, hashes = {};
  for (const [name, expected] of Object.entries(FIXED_HASHES)) {
    const bytes = await fs.readFile(path.join(directory, name));
    hashes[name] = sha256(bytes);
    if (hashes[name] !== expected) throw new Error('Frozen input changed: ' + name);
    parsed[name] = JSON.parse(bytes);
  }
  return { protocol: parsed['protocol.json'], tasks: parsed['tasks.json'], pressure: parsed['pressure-history.json'], hashes };
}

export function grade(task, answer) {
  const original = String(answer ?? '').trim();
  let text = original;
  const fence = text.match(/^```(?:json)?\s*\n([\s\S]*?)\n```$/i);
  if (fence) text = fence[1].trim();
  let value;
  try { value = JSON.parse(text); } catch { return { pass: false, reason: 'invalid-json' }; }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return { pass: false, reason: 'not-object' };
  for (const [key, expected] of Object.entries(task.expected)) {
    if (!Object.hasOwn(value, key) || JSON.stringify(value[key]) !== JSON.stringify(expected)) return { pass: false, reason: 'field-mismatch', field: key };
  }
  for (const blocked of task.prohibited ?? []) if (original.includes(blocked)) return { pass: false, reason: 'prohibited-content' };
  return { pass: true, reason: 'exact-fields' };
}

export class Budget {
  constructor(limits) { this.limits = limits; this.requests = 0; this.reservedTokens = 0; }
  instance() { return { requests: 0, reservedTokens: 0, denied: null }; }
  admit(instance, amount) {
    const n = Number(amount);
    let reason = null;
    if (!Number.isSafeInteger(n) || n <= 0) reason = 'invalid-reservation';
    else if (this.requests >= this.limits.maxRequests) reason = 'global-request-cap';
    else if (this.reservedTokens + n > this.limits.maxReservedTokens) reason = 'global-token-cap';
    else if (instance.requests >= this.limits.perInstanceRequests) reason = 'instance-request-cap';
    else if (instance.reservedTokens + n > this.limits.perInstanceReservedTokens) reason = 'instance-token-cap';
    if (reason) { instance.denied = reason; return false; }
    this.requests++; this.reservedTokens += n; instance.requests++; instance.reservedTokens += n;
    return true;
  }
}

export async function claimRun(runDirectory, metadata) {
  await fs.mkdir(runDirectory, { recursive: true });
  const marker = path.join(runDirectory, 'STARTED');
  // Exclusive file creation is the retry barrier. A crash deliberately leaves this marker.
  const handle = await fs.open(marker, 'wx', 0o600);
  try { await handle.writeFile(JSON.stringify(metadata, null, 2) + '\n'); } finally { await handle.close(); }
  return marker;
}

export async function prepareRuntimeFixture(runDirectory, task, group) {
  const root = path.join(runDirectory, 'runtime-data', task.id + '-' + group);
  const ownerId = randomUUID().replaceAll('-', '');
  const projectId = randomUUID(), sessionId = randomUUID();
  const ownerRoot = path.join(root, ownerId);
  const projectRoot = path.join(ownerRoot, 'projects', projectId);
  const workspace = path.join(projectRoot, 'workspace');
  const sessionDir = path.join(ownerRoot, 'runtime', sessionId);
  await fs.mkdir(workspace, { recursive: true }); await fs.mkdir(sessionDir, { recursive: true });
  await fs.writeFile(path.join(workspace, 'README.md'), '# Isolated memory evaluation\nNo shell or external side effects. Follow the current task.\n');
  const documents = new Map();
  const current = { schema: 1, ownerId, projectId, revision: 0, enabled: true, autoExtract: false, items: [] };
  documents.set(task.scope, current);
  for (const [index, operation] of task.memory.entries()) {
    let document = documents.get(operation.scope);
    if (!document) {
      document = { schema: 1, ownerId, projectId: randomUUID(), revision: 0, enabled: true, autoExtract: false, items: [] };
      documents.set(operation.scope, document);
    }
    const timestamp = new Date(Date.UTC(2026, 0, 1, 0, index)).toISOString();
    let item = document.items.find(entry => entry.key === operation.key);
    if (operation.op === 'remember') {
      if (item) throw new Error('Fixture duplicate remember key');
      item = { id: randomUUID(), key: operation.key, content: operation.content, kind: operation.kind, status: 'ACTIVE', version: 1, source: 'USER', sourceSessionId: operation.session, createdAt: timestamp, updatedAt: timestamp, retiredHashes: [] };
      document.items.push(item);
    } else {
      if (!item) throw new Error('Fixture operation requires an existing record');
      item.retiredHashes.push(sha256(item.content));
      item.version++; item.updatedAt = timestamp;
      if (operation.op === 'correct') item.content = operation.content;
      else if (operation.op === 'forget') { item.status = 'FORGOTTEN'; item.content = ''; }
      else throw new Error('Unknown fixture operation');
    }
    document.revision++;
  }
  // These are synthetic documents in the production schema. Java CRUD is tested separately.
  for (const document of documents.values()) {
    const folder = path.join(ownerRoot, 'projects', document.projectId);
    await fs.mkdir(folder, { recursive: true });
    await fs.writeFile(path.join(folder, 'memory.json'), JSON.stringify(document), { mode: 0o600 });
  }
  return { workspace, sessionDir, memoryFile: path.join(projectRoot, 'memory.json'), ownerId, projectId };
}

export async function seedHistory(SessionManager, fixture, messages) {
  if (!messages?.length) return;
  const session = SessionManager.create(fixture.workspace, fixture.sessionDir);
  for (const [index, message] of messages.entries()) {
    const timestamp = Date.UTC(2026, 0, 2, 0, index);
    if (message.role === 'user') session.appendMessage({ role: 'user', content: [{ type: 'text', text: message.content }], timestamp });
    else session.appendMessage({
      role: 'assistant', content: [{ type: 'text', text: message.content }],
      api: 'openai-completions', provider: 'synthetic-history', model: 'no-provider-called',
      // All-zero usage is ignored by pi; the SDK estimates the imported transcript.
      usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
      stopReason: 'stop', timestamp,
    });
  }
}

export function summarize(results, protocol, mode) {
  const groups = {};
  for (const group of Object.keys(protocol.groups)) {
    const rows = results.filter(row => row.group === group);
    groups[group] = {
      attempted: rows.length, completed: rows.filter(row => row.status === 'completed').length,
      qualityEvaluated: rows.filter(row => typeof row.quality === 'boolean').length,
      qualityPassed: rows.filter(row => row.quality === true).length,
      taskQualityEvaluated: rows.filter(row => row.target !== 'safety' && typeof row.quality === 'boolean').length,
      taskQualityPassed: rows.filter(row => row.target !== 'safety' && row.quality === true).length,
      safetyEvaluated: rows.filter(row => row.target === 'safety' && typeof row.quality === 'boolean').length,
      safetyPassed: rows.filter(row => row.target === 'safety' && row.quality === true).length,
      budgetExhausted: rows.filter(row => row.status === 'budget_exhausted').length,
      failures: rows.filter(row => !['completed', 'budget_exhausted'].includes(row.status)).length,
      requests: rows.reduce((n, row) => n + row.requests, 0),
      reservedTokens: rows.reduce((n, row) => n + row.reservedTokens, 0),
      knownInputTokens: rows.reduce((n, row) => n + row.inputTokens, 0),
      knownOutputTokens: rows.reduce((n, row) => n + row.outputTokens, 0),
      unknownUsageRequests: rows.reduce((n, row) => n + row.unknownUsageRequests, 0),
      latencyMs: rows.reduce((n, row) => n + row.latencyMs, 0),
      actualCompactions: rows.reduce((n, row) => n + row.compactions, 0),
    };
  }
  const paired = {};
  for (const [first, second] of [['B', 'A'], ['C', 'A'], ['D', 'B'], ['D', 'C']]) {
    const differences = [];
    for (const id of protocol.taskOrder) {
      const a = results.find(row => row.taskId === id && row.group === first);
      const b = results.find(row => row.taskId === id && row.group === second);
      if (typeof a?.quality === 'boolean' && typeof b?.quality === 'boolean') differences.push(Number(a.quality) - Number(b.quality));
    }
    paired[first + '-' + second] = { validPairs: differences.length, totalPairs: protocol.taskOrder.length, delta: differences.length ? differences.reduce((a,b) => a+b,0) / differences.length : null };
  }
  return { kind: mode === 'real' ? 'self-built-model-quality-pilot' : 'offline-engineering-only', groups, paired, fees: 'UNKNOWN', claims: 'No official benchmark score; no population or significance claim. Short-term attribution requires actual compaction. Imported synthetic history is not paid model output.' };
}

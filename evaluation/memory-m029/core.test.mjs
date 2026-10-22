import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Budget, claimRun, grade, loadFrozen, prepareRuntimeFixture, summarize } from './core.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));
async function temporary(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'm029-eval-'));
  t.after(async () => {
    assert.equal(path.dirname(path.resolve(root)), path.resolve(os.tmpdir()));
    assert.ok(path.basename(root).startsWith('m029-eval-'));
    await fs.rm(root, { recursive: true, force: true });
  });
  return root;
}

test('E01: frozen protocol, all eight tasks, pressure history and four unique groups validate', async () => {
  const { protocol, tasks, pressure, hashes } = await loadFrozen(directory);
  assert.equal(tasks.length, 8);
  assert.deepEqual(tasks.map(t => t.id), protocol.taskOrder);
  assert.equal(new Set(Object.values(protocol.groups).map(g => JSON.stringify(g))).size, 4);
  assert.equal(Object.keys(hashes).length, 3);
  for (const id of ['C05','C06']) {
    assert.equal(pressure.history[id].length, 26);
    assert.ok(JSON.stringify(pressure.history[id]).length > 15000);
    assert.equal(pressure.history[id].some(row => Object.hasOwn(row, 'expected')), false);
  }
});

test('E02: rubric requires field presence, exact types and raw prohibited-value absence', () => {
  const task = { expected: { n: 7, optional: null }, prohibited: ['STALE'] };
  assert.equal(grade(task, '{"n":7,"optional":null}').pass, true);
  for (const text of ['{"n":"7","optional":null}','{"n":7}','{"n":7,"optional":null,"note":"STALE"}','[7,null]','answer: {"n":7,"optional":null}']) assert.equal(grade(task, text).pass, false);
});

test('E03: one JSON markdown fence is accepted, surrounding prose is rejected', () => {
  const task = { expected: { ok: true }, prohibited: [] };
  assert.equal(grade(task, '```json\n{"ok":true}\n```').pass, true);
  assert.equal(grade(task, 'Here is it:\n```json\n{"ok":true}\n```').pass, false);
});

test('E04: per-instance budget counts every request including summary and never refunds', () => {
  const budget = new Budget({ maxRequests: 10, maxReservedTokens: 1000, perInstanceRequests: 2, perInstanceReservedTokens: 500 });
  const instance = budget.instance();
  assert.equal(budget.admit(instance, 200), true);
  assert.equal(budget.admit(instance, 200), true);
  assert.equal(budget.admit(instance, 1), false);
  assert.equal(instance.denied, 'instance-request-cap');
  assert.equal(budget.reservedTokens, 400);
});

test('E05: global/token/invalid reservation denies fail-closed without consuming failed attempts', () => {
  const limits = { maxRequests: 2, maxReservedTokens: 500, perInstanceRequests: 2, perInstanceReservedTokens: 400 };
  const budget = new Budget(limits);
  assert.equal(budget.admit(budget.instance(), NaN), false);
  assert.equal(budget.admit(budget.instance(), -1), false);
  assert.equal(budget.admit(budget.instance(), 450), false);
  assert.equal(budget.admit(budget.instance(), 300), true);
  const instance = budget.instance();
  assert.equal(budget.admit(instance, 201), false);
  assert.equal(instance.denied, 'global-token-cap');
  assert.equal(budget.admit(instance, 200), true);
  assert.equal(budget.admit(budget.instance(), 1), false);
  assert.equal(budget.requests, 2);
  assert.equal(budget.reservedTokens, 500);
});

test('E06: STARTED is an exclusive crash-retained retry barrier', async t => {
  const root = await temporary(t);
  await claimRun(root, { test: true });
  await assert.rejects(claimRun(root, { test: true }), { code: 'EEXIST' });
  assert.equal(JSON.parse(await fs.readFile(path.join(root, 'STARTED'))).test, true);
});

test('E07: parallel claims allow exactly one run and do not overwrite first metadata', async t => {
  const root = await temporary(t);
  const outcomes = await Promise.allSettled([claimRun(root, { run: 'a' }), claimRun(root, { run: 'b' })]);
  assert.equal(outcomes.filter(x => x.status === 'fulfilled').length, 1);
  assert.equal(outcomes.filter(x => x.status === 'rejected' && x.reason.code === 'EEXIST').length, 1);
});

test('E08: runtime fixtures bind unique owners/projects, exclude foreign project from selected memory', async t => {
  const root = await temporary(t), { tasks } = await loadFrozen(directory);
  const task = tasks.find(t => t.id === 'C04');
  const a = await prepareRuntimeFixture(root, task, 'A'), d = await prepareRuntimeFixture(root, task, 'D');
  assert.notEqual(a.ownerId, d.ownerId); assert.notEqual(a.projectId, d.projectId);
  const document = JSON.parse(await fs.readFile(a.memoryFile));
  assert.equal(document.ownerId, a.ownerId);
  assert.equal(document.projectId, a.projectId);
  assert.equal(document.items.length, 0);
  assert.match(a.sessionDir, /runtime/);
  assert.equal(path.dirname(a.memoryFile), path.dirname(a.workspace));
});

test('E09: corrected fixture retains only active new content; forgotten content erased in runtime fixture', async t => {
  const root = await temporary(t), { tasks } = await loadFrozen(directory);
  const corrected = await prepareRuntimeFixture(root, tasks.find(t => t.id === 'C02'), 'D');
  const doc = JSON.parse(await fs.readFile(corrected.memoryFile));
  assert.equal(doc.items.length, 1);
  assert.equal(doc.items[0].version, 2);
  assert.equal(doc.items[0].retiredHashes.length, 1);
  const forgotten = await prepareRuntimeFixture(root, tasks.find(t => t.id === 'C03'), 'D');
  const removed = JSON.parse(await fs.readFile(forgotten.memoryFile));
  assert.equal(removed.items[0].content, '');
  assert.equal(removed.items[0].status, 'FORGOTTEN');
});

test('E10: missing/failure/budget rows stay in attempt denominator and out of paired quality', async () => {
  const { protocol } = await loadFrozen(directory);
  const row = (group, quality, status) => ({ taskId: 'C01', group, quality, status, target: 'long-term', requests: 1, reservedTokens: 100, inputTokens: 0, outputTokens: 0, unknownUsageRequests: 1, latencyMs: 1, compactions: 0 });
  const summary = summarize([row('A', false, 'completed'), row('B', null, 'budget_exhausted'), row('C', true, 'completed'), row('D', null, 'runtime_error')], protocol, 'real');
  assert.equal(summary.groups.B.attempted, 1);
  assert.equal(summary.groups.B.qualityEvaluated, 0);
  assert.equal(summary.groups.B.budgetExhausted, 1);
  assert.equal(summary.paired['B-A'].validPairs, 0);
  assert.equal(summary.paired['C-A'].delta, 1);
  assert.equal(summary.paired['C-A'].totalPairs, 8);
  assert.equal(summary.fees, 'UNKNOWN');
});

test('E11: offline result summaries never claim model quality or real provider cost', async () => {
  const { protocol } = await loadFrozen(directory);
  const summary = summarize([], protocol, 'offline');
  assert.equal(summary.kind, 'offline-engineering-only');
  assert.equal(summary.fees, 'UNKNOWN');
});

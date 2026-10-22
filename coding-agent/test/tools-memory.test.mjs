import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { ToolRuntime } from '../src/tools.mjs';
import { ContextStore } from '../src/context-store.mjs';
async function fixture(t, options = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'tools-memory-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const workspace = path.join(root, 'workspace'); await fs.mkdir(workspace);
  const abort = new AbortController();
  const store = await new ContextStore({ sessionDir: path.join(root, 'session'), signal: abort.signal }).init();
  const events = [];
  const tools = new ToolRuntime({ workspace, contextStore: store, signal: abort.signal, emit: e => events.push(e), approvalMode: 'allow', ...options });
  await tools.init(); return { root, workspace, store, tools, abort, events };
}
test('small read is unchanged; long read yields explicit page and recoverable snapshot', async t => {
  const { tools, workspace, store } = await fixture(t);
  await fs.writeFile(path.join(workspace, 'small.txt'), 'hello');
  assert.equal((await tools.execute('read', { path: 'small.txt' })).content[0].text, 'hello');
  const raw = Array.from({ length: 700 }, (_, i) => 'line ' + i + ' ' + 'data'.repeat(8) + '\n').join('');
  await fs.writeFile(path.join(workspace, 'large.txt'), raw);
  const first = await tools.execute('read', { path: 'large.txt' });
  assert.ok(Buffer.byteLength(first.content[0].text) < 8700);
  assert.match(first.content[0].text, /More content/); assert.match(first.content[0].text, /Archived snapshot/);
  const second = await tools.execute('read', { path: 'large.txt', offset: first.details.page.nextOffset, charOffset: first.details.page.nextCharOffset });
  assert.ok(second.content[0].text.startsWith(raw.slice(7800, 7850)));
  assert.equal((await store.list()).total, 1);
  assert.equal(second.details.contextRef.id, first.details.contextRef.id);
});
test('line pagination and very long single-line resume do not silently drop tail', async t => {
  const { tools, workspace } = await fixture(t);
  await fs.writeFile(path.join(workspace, 'lines.txt'), 'zero\none\ntwo\n');
  const page = await tools.execute('read', { path: 'lines.txt', offset: 1, limit: 1 });
  assert.ok(page.content[0].text.startsWith('one\n')); assert.equal(page.details.page.nextOffset, 2);
  const raw = 'a'.repeat(15000) + 'THE_END'; await fs.writeFile(path.join(workspace, 'single.txt'), raw);
  const first = await tools.execute('read', { path: 'single.txt' });
  assert.equal(first.details.page.nextOffset, 0); assert.equal(first.details.page.nextCharOffset, 7800);
  const second = await tools.execute('read', { path: 'single.txt', offset: 0, charOffset: 7800 });
  assert.match(second.content[0].text, /THE_END/); assert.equal(second.details.page.nextOffset, null);
});
test('recall restores immutable old output after workspace changes and does not rearchive', async t => {
  const { tools, workspace, store } = await fixture(t);
  const original = 'ORIGINAL '.repeat(1500); await fs.writeFile(path.join(workspace, 'a.txt'), original);
  const result = await tools.execute('read', { path: 'a.txt' });
  await fs.writeFile(path.join(workspace, 'a.txt'), 'replaced');
  let offset = 0, chunks = '';
  do {
    const recalled = await tools.execute('recall', { id: result.details.contextRef.id, offset });
    chunks += recalled.content[0].text.split('\n[Archived snapshot id=')[0];
    offset = recalled.details.recall.nextOffset;
  } while (offset !== null);
  assert.equal(chunks, original); assert.equal((await store.list()).total, 1);
  const listing = await tools.execute('recall', {});
  assert.equal(JSON.parse(listing.content[0].text).entries[0].id, result.details.contextRef.id);
});
test('archive failure is explicit and never produces fake references or repeats commands', async t => {
  const { tools, workspace } = await fixture(t);
  tools.contextStore = { put: async () => { throw new Error('disk full'); } };
  await fs.writeFile(path.join(workspace, 'a.txt'), 'x'.repeat(12000));
  const read = await tools.execute('read', { path: 'a.txt' });
  assert.match(read.content[0].text, /NOT recoverable/); assert.equal(read.details.contextRef, undefined);
  let executions = 0;
  tools.allowShell = true; tools.runCommand = async () => { executions++; return 'log'.repeat(4000); };
  const command = await tools.execute('bash', { command: 'synthetic test only' });
  assert.equal(executions, 1); assert.match(command.content[0].text, /Do not repeat/);
  assert.ok(command.details.archiveError); assert.equal(command.details.contextRef, undefined);
});
test('write and edit keep existing behavior and never archive tool acknowledgments', async t => {
  const { tools, store } = await fixture(t);
  tools.contextStore = { put: async () => { throw new Error('must not be called'); } };
  await tools.execute('write', { path: 'new.txt', content: 'old' });
  await tools.execute('edit', { path: 'new.txt', oldText: 'old', newText: 'new' });
  assert.equal((await store.list()).total, 0);
});
test('recall preserves approval, tool-count, cancellation and disabled-shell boundaries', async t => {
  const { tools, store, events, abort } = await fixture(t, { approvalMode: 'ask' });
  const ref = await store.put('snapshot');
  const denied = tools.execute('recall', { id: ref.id });
  tools.approve(events.at(-1).approvalId, false); await assert.rejects(denied, /rejected/);
  tools.approvalMode = 'allow'; tools.count = 80;
  await assert.rejects(tools.execute('recall', { id: ref.id }), /Tool limit/);
  tools.count = 0; await assert.rejects(tools.execute('bash', { command: 'noop' }), /disabled/);
  abort.abort(); await assert.rejects(tools.execute('recall', { id: ref.id }), /abort/i);
});
test('invalid read pagination and protected paths remain rejected', async t => {
  const { tools, workspace } = await fixture(t);
  await fs.writeFile(path.join(workspace, 'a.txt'), 'line');
  for (const args of [{ offset: -1 }, { limit: 0 }, { limit: 2001 }, { charOffset: 9 }, { offset: 0.5 }]) await assert.rejects(tools.execute('read', { path: 'a.txt', ...args }));
  await assert.rejects(tools.execute('read', { path: '../session/context-artifacts' }), /Protected/);
  await assert.rejects(tools.execute('recall', { id: '../session' }), /Invalid context reference/);
});


test('long directory listing has a recoverable bounded snapshot', async t => {
  const { tools, workspace, store } = await fixture(t);
  await Promise.all(Array.from({ length: 250 }, (_, i) => fs.writeFile(path.join(workspace, String(i).padStart(3, '0') + '-long-file-name-for-listing.txt'), '')));
  const result = await tools.execute('list', { path: '.' });
  assert.ok(result.details.contextRef); assert.ok(Buffer.byteLength(result.content[0].text) < 8600);
  const snapshot = await store.read({ id: result.details.contextRef.id, offset: 8000 });
  assert.match(snapshot.text, /249-long-file-name/);
});
test('cancellation during archival does not emit a successful tool result', async t => {
  const { tools, workspace, abort, events } = await fixture(t);
  await fs.writeFile(path.join(workspace, 'long.txt'), 'a'.repeat(3000));
  tools.contextStore = { put: async () => { abort.abort(); abort.signal.throwIfAborted(); } };
  await assert.rejects(tools.execute('read', { path: 'long.txt' }), /abort/i);
  assert.equal(events.filter(event => event.type === 'tool_end' && event.ok).length, 0);
});


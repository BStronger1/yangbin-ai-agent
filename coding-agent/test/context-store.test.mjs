import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { ContextStore, CONTEXT_LIMITS, boundedText } from '../src/context-store.mjs';

async function fixture(t, options = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'context-store-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const controller = new AbortController();
  const store = new ContextStore({ sessionDir: path.join(root, 'session'), signal: controller.signal, ...options });
  await store.init(); return { root, store, controller };
}
test('archive persists and reloads exact Unicode with bounded cursor pages', async t => {
  const { store } = await fixture(t);
  const text = '中文🙂\n'.repeat(4000);
  const ref = await store.put(text, { kind: 'read', source: 'src/file.txt' });
  assert.match(ref.id, /^[a-f0-9]{32}$/);
  const restored = await new ContextStore({ sessionDir: store.sessionDir }).init();
  let offset = 0, result = '';
  do {
    const page = await restored.read({ id: ref.id, offset });
    assert.ok(Buffer.byteLength(page.text) <= 8192);
    assert.ok(!page.text.includes('\uFFFD')); result += page.text; offset = page.nextOffset;
  } while (offset !== null);
  assert.equal(result, text);
  assert.equal((await restored.list()).entries[0].source, 'src/file.txt');
});
test('redaction occurs before persistence and duplicate snapshots reuse reference', async t => {
  const { store } = await fixture(t, { redact: text => text.replaceAll('secret-value', '[REDACTED]') });
  const first = await store.put('secret-value text', { source: 'secret-value' });
  const second = await store.put('secret-value text', { source: 'secret-value' });
  assert.equal(first.id, second.id);
  const onDisk = await fs.readFile(path.join(store.directory, first.id + '.json'), 'utf8');
  assert.ok(!onDisk.includes('secret-value'));
  assert.equal((await store.list()).total, 1);
});
test('opaque references cannot access another session or arbitrary paths', async t => {
  const { root, store } = await fixture(t);
  const ref = await store.put('account A');
  const other = await new ContextStore({ sessionDir: path.join(root, 'other') }).init();
  await assert.rejects(other.read({ id: ref.id }), { code: 'ENOENT' });
  for (const id of ['../x', 'C:/x', ref.id + '.json', undefined]) await assert.rejects(store.read({ id }), /Invalid context reference/);
});
test('invalid pages and oversized items are rejected without deleting history', async t => {
  const { store } = await fixture(t); const ref = await store.put('kept');
  await assert.rejects(store.put('x'.repeat(CONTEXT_LIMITS.itemBytes + 1)), /bounds/);
  for (const pagination of [{ offset: -1 }, { limit: 0 }, { limit: 8193 }, { offset: 999 }, { offset: 0.5 }]) await assert.rejects(store.read({ id: ref.id, ...pagination }));
  await assert.rejects(store.list({ limit: 51 }));
  assert.equal((await store.read({ id: ref.id })).text, 'kept');
});
test('corruption and incomplete writes fail closed on recovery', async t => {
  const { store } = await fixture(t); const ref = await store.put('original');
  const file = path.join(store.directory, ref.id + '.json');
  const record = JSON.parse(await fs.readFile(file, 'utf8')); record.text = 'changed';
  await fs.writeFile(file, JSON.stringify(record));
  await assert.rejects(store.read({ id: ref.id }), /Corrupt/);
  await assert.rejects(new ContextStore({ sessionDir: store.sessionDir }).init(), /Corrupt/);
  await fs.writeFile(file, JSON.stringify({ ...record, text: 'original' }));
  await fs.writeFile(path.join(store.directory, 'incomplete.pending'), 'partial');
  await assert.rejects(new ContextStore({ sessionDir: store.sessionDir }).init(), /Invalid context archive/);
});
test('record hardlinks and directory links are rejected', async t => {
  const { root, store } = await fixture(t); const ref = await store.put('private');
  await fs.link(path.join(store.directory, ref.id + '.json'), path.join(root, 'linked.json'));
  await assert.rejects(store.read({ id: ref.id }), /Linked/);
  const alias = path.join(root, 'alias');
  await fs.symlink(store.sessionDir, alias, process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(new ContextStore({ sessionDir: alias }).init(), /Linked/);
});
test('serialized writes retain all entries and ownerless lock fails closed', async t => {
  const { store } = await fixture(t);
  await Promise.all(Array.from({ length: 8 }, (_, i) => store.put('entry' + i)));
  assert.equal((await store.list()).total, 8);
  await fs.mkdir(path.join(store.directory, '.write-lock'));
  await assert.rejects(store.put('blocked'), /owner missing|recovery/);
  assert.equal((await store.list()).total, 8);
});
test('total byte quota is enforced and existing records remain readable', async t => {
  const { store } = await fixture(t);
  let first;
  for (let i = 0; i < 32; i++) { const ref = await store.put(String(i).padStart(2, '0') + 'x'.repeat(CONTEXT_LIMITS.itemBytes - 2)); first ??= ref; }
  assert.equal((await store.list()).bytes, CONTEXT_LIMITS.totalBytes);
  await assert.rejects(store.put('one more'), /quota/);
  assert.equal((await store.read({ id: first.id, limit: 2 })).text, '00');
});
test('record count quota is enforced without deleting records', async t => {
  const { store } = await fixture(t); const text = 'x', sha256 = createHash('sha256').update(text).digest('hex');
  for (let i = 0; i < 512; i++) {
    const id = i.toString(16).padStart(32, '0');
    await fs.writeFile(path.join(store.directory, id + '.json'), JSON.stringify({ version: 1, id, text, sha256, bytes: 1, kind: 'read', source: '', createdAt: new Date(0).toISOString() }));
  }
  await assert.rejects(store.put('different'), /quota/);
  assert.equal((await store.list({ offset: 500 })).entries.length, 12);
});
test('cancellation stops new archive work and UTF-8 cap preserves characters', async t => {
  const { store, controller } = await fixture(t); const ref = await store.put('saved');
  controller.abort();
  await assert.rejects(store.put('cancelled'), /abort/i);
  await assert.rejects(store.read({ id: ref.id }), /abort/i);
  assert.equal(boundedText('🙂🙂', 5), '🙂');
});


function crashBeforeCommit(store, afterCommit = false) {
  const moduleUrl = pathToFileURL(path.resolve(import.meta.dirname, '../src/context-store.mjs')).href;
  const source = [
    'import fs from "node:fs/promises";',
    'import { ContextStore } from ' + JSON.stringify(moduleUrl) + ';',
    'const store = await new ContextStore({sessionDir:process.argv[1]}).init();',
    'const rename = fs.rename; fs.rename = async (from,to) => { if (to.endsWith(".json")) { if (' + afterCommit + ') await rename(from,to); process.exit(7); } return rename(from,to); };',
    'await store.put("interrupted snapshot");'
  ].join('\n');
  const child = spawnSync(process.execPath, ['--input-type=module', '-e', source, store.sessionDir], { encoding: 'utf8', windowsHide: true, timeout: 10000 });
  assert.equal(child.status, 7, child.stderr);
}
test('real child process crash before commit is recovered and committed snapshots are unchanged', async t => {
  const { store } = await fixture(t); const previous = await store.put('committed evidence');
  const file = path.join(store.directory, previous.id + '.json'), before = await fs.readFile(file);
  crashBeforeCommit(store);
  assert.equal((await fs.readdir(path.join(store.directory, '.write-lock'))).length, 2);
  const recovered = await new ContextStore({ sessionDir: store.sessionDir }).init();
  const next = await recovered.put('next snapshot');
  assert.equal((await recovered.read({ id: next.id })).text, 'next snapshot');
  assert.deepEqual(await fs.readFile(file), before);
  assert.equal((await recovered.list()).total, 2);
  await assert.rejects(fs.stat(path.join(store.directory, '.write-lock')), { code: 'ENOENT' });
  await assert.rejects(fs.stat(path.join(store.directory, '.recovering-lock')), { code: 'ENOENT' });
});
test('interrupted quarantine recovery is bounded and concurrent same-process initialization is serialized', async t => {
  const { store } = await fixture(t); await store.put('old');
  crashBeforeCommit(store);
  await fs.rename(path.join(store.directory, '.write-lock'), path.join(store.directory, '.recovering-lock'));
  const stores = await Promise.all([1, 2].map(() => new ContextStore({ sessionDir: store.sessionDir }).init()));
  await Promise.all(stores.map((instance, i) => instance.put('recovered' + i)));
  assert.equal((await stores[0].list()).total, 3);
  await fs.mkdir(path.join(store.directory, '.recovering-lock'));
  await new ContextStore({ sessionDir: store.sessionDir }).init();
  await assert.rejects(fs.stat(path.join(store.directory, '.recovering-lock')), { code: 'ENOENT' });
});
test('active PID lock is never stolen and committed records stay readable', async t => {
  const { store } = await fixture(t); const ref = await store.put('safe');
  const lock = path.join(store.directory, '.write-lock'); await fs.mkdir(lock);
  const owner = JSON.stringify({ pid: process.pid, hostname: os.hostname(), token: 'a'.repeat(32) });
  await fs.writeFile(path.join(lock, 'owner.json'), owner);
  const another = await new ContextStore({ sessionDir: store.sessionDir }).init();
  await assert.rejects(another.put('blocked'), /busy/);
  assert.equal(await fs.readFile(path.join(lock, 'owner.json'), 'utf8'), owner);
  assert.equal((await another.read({ id: ref.id })).text, 'safe');
});
test('dead lock with unknown files is retained for manual recovery', async t => {
  const { store } = await fixture(t); crashBeforeCommit(store);
  const lock = path.join(store.directory, '.write-lock');
  await fs.writeFile(path.join(lock, 'do-not-delete.txt'), 'user data');
  await assert.rejects(new ContextStore({ sessionDir: store.sessionDir }).init(), /unknown|Unknown/);
  assert.equal(await fs.readFile(path.join(lock, 'do-not-delete.txt'), 'utf8'), 'user data');
});
test('hardlinked pending is not deleted during stale recovery', async t => {
  const { root, store } = await fixture(t); crashBeforeCommit(store);
  const lock = path.join(store.directory, '.write-lock');
  const pending = (await fs.readdir(lock)).find(name => name.endsWith('.pending'));
  const external = path.join(root, 'retained');
  await fs.link(path.join(lock, pending), external);
  const before = await fs.readFile(external);
  await assert.rejects(new ContextStore({ sessionDir: store.sessionDir }).init(), /Linked/);
  assert.deepEqual(await fs.readFile(external), before);
  assert.deepEqual(await fs.readFile(path.join(lock, pending)), before);
});
test('unknown-host lock and missing owner never authorize cleanup', async t => {
  const { store } = await fixture(t); crashBeforeCommit(store);
  const ownerPath = path.join(store.directory, '.write-lock', 'owner.json');
  const owner = JSON.parse(await fs.readFile(ownerPath, 'utf8'));
  await fs.writeFile(ownerPath, JSON.stringify({ ...owner, hostname: 'another-host' }));
  await assert.rejects(new ContextStore({ sessionDir: store.sessionDir }).init(), /another host/);
  await fs.unlink(ownerPath);
  await assert.rejects(new ContextStore({ sessionDir: store.sessionDir }).init(), /owner missing/);
  assert.equal((await fs.readdir(path.dirname(ownerPath))).length, 1);
});

test('crash after commit retains that record and existing instance recovers before its next put', async t => {
  const { store } = await fixture(t); await store.put('first');
  crashBeforeCommit(store, true);
  assert.equal((await fs.readdir(path.join(store.directory, '.write-lock'))).length, 1);
  await store.put('third');
  const listing = await store.list(); assert.equal(listing.total, 3);
  const contents = await Promise.all(listing.entries.map(entry => store.read({ id: entry.id }).then(page => page.text)));
  assert.ok(contents.includes('interrupted snapshot')); assert.ok(contents.includes('first')); assert.ok(contents.includes('third'));
});

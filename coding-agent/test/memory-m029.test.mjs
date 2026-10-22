import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { MemoryContext, retrieveMemory, memoryExtension } from '../src/long-term-memory.mjs';

async function fixture(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'memory-m029-')); t.after(() => fs.rm(root, { recursive: true, force: true }));
  const ownerId = 'admin', projectId = randomUUID(), owner = path.join(root, ownerId);
  const project = path.join(owner, 'projects', projectId), workspace = path.join(project, 'workspace'), sessionDir = path.join(owner, 'runtime', randomUUID());
  await fs.mkdir(workspace, { recursive: true }); await fs.mkdir(sessionDir, { recursive: true });
  const file = path.join(project, 'memory.json'), document = { schema: 1, ownerId, projectId, revision: 1, enabled: true, autoExtract: false, items: [
    { id: randomUUID(), key: 'database', content: 'PostgreSQL 16', kind: 'CONSTRAINT', status: 'ACTIVE', version: 1 }
  ] };
  const write = () => fs.writeFile(file, JSON.stringify(document));
  await write(); return { file, workspace, sessionDir, document, write };
}
test('M029 retrieval matches exact key/words/Chinese bigrams and ignores irrelevant/archived', () => {
  const document = { enabled: true, items: [{ id: 'a', key: '数据库', content: '使用 PostgreSQL', kind: 'CONSTRAINT', status: 'ACTIVE', version: 1 }, { id: 'b', key: '数据库', content: 'obsolete', kind: 'FACT', status: 'ARCHIVED', version: 1 }] };
  assert.equal(retrieveMemory(document, '数据库迁移').items.length, 1);
  assert.equal(retrieveMemory(document, 'PostgreSQL').items.length, 1);
  assert.equal(retrieveMemory(document, 'unrelated').items.length, 0);
  document.enabled = false; assert.equal(retrieveMemory(document, '数据库').items.length, 0);
});
test('M029 bounded memory keeps complete records and never truncates a factual value', () => {
  const document = { items: Array.from({ length: 12 }, (_, n) => ({ id: String(n), key: 'database' + n, content: 'x'.repeat(700), status: 'ACTIVE', kind: 'FACT', version: 1 })) };
  const result = retrieveMemory(document, 'database', { budgetBytes: 1024, limit: 4 });
  assert.ok(result.bytes <= 1024); assert.ok(result.characters <= 2048); assert.ok(result.items.length <= 4);
  assert.ok(result.items.every(i => result.text.includes(i.content)));
});
test('M029 restart/new session reads updates and forgets with no cached old content', async t => {
  const f = await fixture(t), events = [], messages = [{ role: 'user', content: 'database', timestamp: 1 }];
  const memory = await new MemoryContext({ ...f, query: 'database', emit: e => events.push(e) }).init();
  assert.match(JSON.stringify(await memory.project(messages)), /PostgreSQL 16/);
  f.document.items[0].content = 'PostgreSQL 17'; f.document.items[0].version++; f.document.revision++; await f.write();
  const changed = JSON.stringify(await memory.project(messages)); assert.match(changed, /PostgreSQL 17/); assert.doesNotMatch(changed, /PostgreSQL 16/);
  const nextSession = path.join(path.dirname(f.sessionDir), randomUUID()); await fs.mkdir(nextSession);
  assert.match(JSON.stringify(await (await new MemoryContext({ ...f, sessionDir: nextSession, query: 'database' }).init()).project(messages)), /PostgreSQL 17/);
  f.document.items[0].status = 'FORGOTTEN'; f.document.items[0].content = ''; f.document.items[0].key = ''; f.document.revision++; await f.write();
  assert.deepEqual(await memory.project(messages), messages);
  assert.equal(events.at(-1).count, 0); assert.equal(messages.length, 1);
  assert.equal((await fs.readdir(nextSession)).length, 0);
});
test('M029 owner/project/path binding refuses foreign or workspace-controlled memory', async t => {
  const f = await fixture(t);
  f.document.ownerId = 'b'.repeat(32); await f.write();
  await assert.rejects(new MemoryContext({ ...f, query: 'database' }).init(), /Invalid memory document/);
  f.document.ownerId = 'admin'; f.document.projectId = randomUUID(); await f.write();
  await assert.rejects(new MemoryContext({ ...f, query: 'database' }).init(), /Invalid memory document/);
  await assert.rejects(new MemoryContext({ ...f, file: path.join(f.workspace, 'memory.json'), query: 'database' }).init(), /scope path/);
});
test('M029 disable/archive and abort take effect on the next request', async t => {
  const f = await fixture(t), controller = new AbortController();
  const memory = await new MemoryContext({ ...f, query: 'database', signal: controller.signal }).init();
  f.document.enabled = false; await f.write(); assert.deepEqual(await memory.project([]), []);
  f.document.enabled = true; f.document.items[0].status = 'ARCHIVED'; await f.write(); assert.deepEqual(await memory.project([]), []);
  controller.abort(); await assert.rejects(memory.project([]), /abort/i);
});
test('M029 corrupt store fails closed and extension does not write/authorize tools', async t => {
  const f = await fixture(t), memory = await new MemoryContext({ ...f, query: 'database' }).init();
  let failure; const extension = memoryExtension(memory, e => { failure = e; }); assert.equal(extension.tools.size, 0);
  await fs.writeFile(f.file, '{'); await assert.rejects(extension.handlers.get('context')[0]({ messages: [] })); assert.ok(failure);
});


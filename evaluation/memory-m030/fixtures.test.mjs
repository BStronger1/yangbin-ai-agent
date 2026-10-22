import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createCase, inspectArtifact } from './fixtures.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));
const tasks = JSON.parse(await fs.readFile(path.join(directory, 'tasks.json'), 'utf8'));
async function root(t) { const value = await fs.mkdtemp(path.join(os.tmpdir(), 'm030-fixture-')); t.after(() => fs.rm(value, { recursive: true, force: true })); return value; }
async function files(folder) { const out = []; for (const entry of await fs.readdir(folder, { withFileTypes: true })) { const file = path.join(folder, entry.name); if (entry.isDirectory()) out.push(...await files(file)); else out.push(file); } return out; }

test('M030 four new tasks have two bounded phases with equal neutral pressure', () => {
  assert.deepEqual(tasks.map(task => task.id), ['N01', 'N02', 'N03', 'N04']);
  for (const task of tasks) {
    assert.equal(task.historyPhases.length, 2); assert.equal(task.requiredCompactions, 2);
    assert.deepEqual(task.sameSessionHistory, task.historyPhases.flatMap(phase => phase.messages));
    for (let phase = 0; phase < 2; phase++) {
      const bytes = Buffer.byteLength(JSON.stringify(task.historyPhases[phase].messages)); assert.ok(bytes >= 22000 && bytes <= 26000);
      assert.deepEqual(task.historyPhases[phase].messages.slice(1), tasks[0].historyPhases[phase].messages.slice(1));
      assert.equal(task.historyPhases[phase].messages.length, 54);
    }
  }
  for (const [index, forbidden] of ['shardWidth=29', 'RIDGE_628', 'reportShape=compact', 'probeWindowSeconds=47'].entries()) {
    assert.ok(tasks[index].historyPhases[0].messages[0].content.includes(forbidden));
    assert.ok(!JSON.stringify(tasks[index].historyPhases[1]).includes(forbidden));
    assert.ok(!tasks[index].prompt.includes(forbidden));
  }
});

test('M030 each task-group receives disjoint owner/project/session/workspace', async t => {
  const directory = await root(t), owners = new Set(), workspaces = new Set();
  for (const task of tasks) for (const group of ['A', 'B', 'C', 'D']) {
    const fixture = await createCase(directory, task, group);
    assert.ok(!owners.has(fixture.ownerId)); assert.ok(!workspaces.has(fixture.workspace)); owners.add(fixture.ownerId); workspaces.add(fixture.workspace);
    assert.ok(fixture.memoryFile.startsWith(path.dirname(fixture.workspace)));
    assert.notEqual(path.dirname(fixture.sessionDir), fixture.workspace);
    assert.equal((await fs.readdir(fixture.sessionDir)).length, 0, 'caller owns SDK seeding');
    assert.equal(Object.hasOwn(fixture, 'expected'), false); assert.equal(Object.hasOwn(fixture, 'prohibited'), false);
  }
  assert.equal(owners.size, 16); assert.equal(workspaces.size, 16);
});

test('M030 runtime memory contains only corrected active version, forgotten tombstone and scoped current fact', async t => {
  const directory = await root(t);
  const corrected = await createCase(directory, tasks[1], 'D');
  const second = JSON.parse(await fs.readFile(corrected.memoryFile, 'utf8'));
  assert.equal(second.items[0].version, 2); assert.ok(second.items[0].content.includes('389')); assert.ok(!second.items[0].content.includes('263'));
  const forgotten = await createCase(directory, tasks[2], 'D');
  const third = JSON.parse(await fs.readFile(forgotten.memoryFile, 'utf8'));
  assert.equal(third.items[0].status, 'FORGOTTEN'); assert.equal(third.items[0].content, ''); assert.equal(third.items[0].key, ''); assert.equal(third.items[0].keyHash.length, 64);
  assert.ok(!JSON.stringify(third).includes('MAUVE_OTTER_856')); assert.equal(third.items[1].status, 'ACTIVE');
  const scoped = await createCase(directory, tasks[3], 'D');
  const fourth = JSON.parse(await fs.readFile(scoped.memoryFile, 'utf8'));
  assert.equal(fourth.items.length, 1); assert.ok(fourth.items[0].content.includes('mesh-linden')); assert.ok(!JSON.stringify(fourth).includes('mesh-juniper'));
  const projectFolders = await fs.readdir(path.dirname(path.dirname(scoped.workspace)));
  assert.equal(projectFolders.length, 2, 'foreign project exists outside current workspace');
});

test('M030 answer keys and evaluator files never enter model-visible workspace', async t => {
  const directory = await root(t);
  for (const task of tasks) {
    const fixture = await createCase(directory, task, 'A');
    const visible = await files(fixture.workspace);
    const relative = visible.map(file => path.relative(fixture.workspace, file).replaceAll(path.sep, '/')).sort();
    assert.deepEqual(relative, task.artifact ? ['README.md', task.artifact.path].sort() : ['README.md']);
    const texts = (await Promise.all(visible.map(file => fs.readFile(file, 'utf8')))).join('\n');
    for (const marker of ['prohibited', 'historyPhases', 'requiredCompactions', 'cooldownSeconds', 'MAUVE_OTTER_856', 'mesh-juniper', 'RIDGE_628']) assert.ok(!texts.includes(marker));
    if (task.artifact) { assert.deepEqual(JSON.parse(await fs.readFile(fixture.artifactPath, 'utf8')), task.artifact.initial); assert.ok(!texts.includes('vault-cobalt')); }
  }
});

test('M030 artifact inspection validates file object, not a claimed model answer', async t => {
  const directory = await root(t), task = tasks[0], fixture = await createCase(directory, task, 'D');
  assert.equal((await inspectArtifact(fixture, task)).score.pass, false, 'unmodified initial artifact cannot pass');
  await fs.writeFile(fixture.artifactPath, JSON.stringify(task.artifact.expected));
  const result = await inspectArtifact(fixture, task); assert.equal(result.exists, true); assert.equal(result.schema, 'json-object'); assert.equal(result.score.pass, true); assert.deepEqual(result.actual, task.artifact.expected);
  await fs.writeFile(fixture.artifactPath, JSON.stringify({ ...task.artifact.expected, unexpected: true })); assert.equal((await inspectArtifact(fixture, task)).score.pass, false);
  await fs.writeFile(fixture.artifactPath, 'globalThis.mustNotRun = true'); assert.equal((await inspectArtifact(fixture, task)).schema, 'invalid-json'); assert.equal(globalThis.mustNotRun, undefined);
  await fs.writeFile(fixture.artifactPath, '[]'); assert.equal((await inspectArtifact(fixture, task)).schema, 'non-object');
  await fs.writeFile(fixture.artifactPath, 'x'.repeat(65537)); assert.equal((await inspectArtifact(fixture, task)).schema, 'invalid-size-or-kind');
  await fs.unlink(fixture.artifactPath); assert.equal((await inspectArtifact(fixture, task)).score.reason, 'artifact-missing');
});

test('M030 path traversal and invalid group fail closed; no-task artifact check is explicit', async t => {
  const directory = await root(t);
  await assert.rejects(createCase(directory, tasks[0], 'E'), /scope/);
  await assert.rejects(createCase(directory, { ...tasks[0], artifact: { ...tasks[0].artifact, path: '../answer.json' } }, 'A'), /artifact path/);
  assert.deepEqual(await inspectArtifact({}, tasks[1]), { required: false, exists: false, schema: null, actual: null, score: null });
});


import fs from 'node:fs/promises';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { prepareRuntimeFixture, sha256 } from '../memory-m029/core.mjs';

const validGroups = new Set(['A', 'B', 'C', 'D']);
function artifactFile(workspace, name) {
  if (typeof name !== 'string' || !/^[a-zA-Z0-9_-]+(?:\/[a-zA-Z0-9_.-]+)*\.json$/.test(name) || name.split('/').some(segment => segment === '.' || segment === '..')) throw new Error('Invalid artifact path');
  const file = path.resolve(workspace, ...name.split('/'));
  if (!file.startsWith(path.resolve(workspace) + path.sep)) throw new Error('Artifact outside workspace');
  return file;
}

/**
 * Synthetic runtime fixtures, not Java CRUD coverage. No SDK/model import or request.
 * The caller seeds phase one and appends phase two to that SAME persisted session.
 * expected/prohibited/component labels stay in the evaluator; never copy task JSON to workspace.
 */
export async function createCase(root, task, group) {
  if (!path.isAbsolute(root) || !validGroups.has(group) || !/^N0[1-4]$/.test(task.id)) throw new Error('Invalid case scope');
  if (!Array.isArray(task.historyPhases) || task.historyPhases.length !== 2 || task.historyPhases.some(phase => !Array.isArray(phase.messages) || Buffer.byteLength(JSON.stringify(phase.messages)) >= 32000)) throw new Error('Two bounded history phases required');
  const fixture = await prepareRuntimeFixture(root, task, group);
  // Match the production forgotten-record representation without modifying frozen M029.
  const document = JSON.parse(await fs.readFile(fixture.memoryFile, 'utf8'));
  for (const item of document.items) {
    item.keyHash = sha256(item.key.trim().toLowerCase().replace(/\s+/g, ' '));
    item.retiredKeys = [];
    if (item.status === 'FORGOTTEN') { item.key = ''; item.content = ''; item.sourceSessionId = null; }
  }
  await fs.writeFile(fixture.memoryFile, JSON.stringify(document), { mode: 0o600 });
  let artifactPath;
  if (task.artifact) {
    artifactPath = artifactFile(fixture.workspace, task.artifact.path);
    if (!task.artifact.initial || typeof task.artifact.initial !== 'object' || Array.isArray(task.artifact.initial)) throw new Error('Artifact initial must be a JSON object');
    await fs.mkdir(path.dirname(artifactPath), { recursive: true });
    await fs.writeFile(artifactPath, JSON.stringify(task.artifact.initial, null, 2) + '\n', { flag: 'wx' });
  }
  // No answer keys in returned runtime fields. Pass only this fixture's paths to runAgent.
  return { ...fixture, artifactPath, historyPhases: structuredClone(task.historyPhases), stage1Prompt: task.stage1Prompt };
}

/** Parent-only JSON validation; never eval/import/execute model-produced content. */
export async function inspectArtifact(fixture, task) {
  if (!task.artifact) return { required: false, exists: false, schema: null, actual: null, score: null };
  const file = artifactFile(fixture.workspace, task.artifact.path);
  try {
    for (let current = file; current !== path.dirname(path.resolve(fixture.workspace)); current = path.dirname(current)) {
      const stat = await fs.lstat(current);
      if (stat.isSymbolicLink()) return { required: true, exists: true, schema: 'unsafe-link', actual: null, score: { pass: false, reason: 'symlink-rejected' } };
    }
    const stat = await fs.stat(file);
    if (!stat.isFile() || stat.size > 65536) return { required: true, exists: true, schema: 'invalid-size-or-kind', actual: null, score: { pass: false, reason: 'artifact-size-or-kind' } };
    let actual;
    try { actual = JSON.parse(await fs.readFile(file, 'utf8')); }
    catch { return { required: true, exists: true, schema: 'invalid-json', actual: null, score: { pass: false, reason: 'invalid-json' } }; }
    if (!actual || typeof actual !== 'object' || Array.isArray(actual)) return { required: true, exists: true, schema: 'non-object', actual, score: { pass: false, reason: 'not-object' } };
    const pass = isDeepStrictEqual(actual, task.artifact.expected);
    return { required: true, exists: true, schema: 'json-object', actual, bytes: stat.size, score: { pass, reason: pass ? 'exact-file-object' : 'file-field-mismatch' } };
  } catch (error) {
    if (error.code === 'ENOENT') return { required: true, exists: false, schema: null, actual: null, score: { pass: false, reason: 'artifact-missing' } };
    throw error;
  }
}


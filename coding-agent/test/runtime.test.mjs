import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import readline from 'node:readline';
import { ToolRuntime } from '../src/tools.mjs';

async function fixture(t, options = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'yangbin-coding-test-'));
  const workspace = path.join(root, 'workspace'); await fs.mkdir(workspace);
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const abort = new AbortController(); const events = [];
  const tools = new ToolRuntime({ workspace, approvalMode: 'allow', signal: abort.signal, emit: event => events.push(event), ...options }); await tools.init();
  return { root, workspace, tools, events, abort };
}
test('real file operations, unique edit and protected path rejection', async t => {
  const { workspace, tools } = await fixture(t);
  await tools.execute('write', { path: 'src/a.txt', content: 'hello world' });
  await tools.execute('edit', { path: 'src/a.txt', oldText: 'world', newText: 'pi' });
  assert.equal((await tools.execute('read', { path: 'src/a.txt' })).content[0].text, 'hello pi');
  assert.equal(await fs.readFile(path.join(workspace, 'src/a.txt'), 'utf8'), 'hello pi');
  for (const name of ['../outside', '/outside', '.env', '.env.local', '.git/config', 'C:/secrets', 'file:secret', 'a\\b', 'secrets.json', '.ENV', '.Git/config', '.SSH/id_rsa', '.env. ', 'CON', 'aux.txt', 'foo.']) await assert.rejects(tools.execute('read', { path: name }));
  await assert.rejects(tools.execute('edit', { path: 'src/a.txt', oldText: 'missing', newText: 'x' }), /exactly once/);
});
test('approval denial, allow-once and cancel never mutate before acceptance', async t => {
  const { tools, events, abort, workspace } = await fixture(t, { approvalMode: 'ask' });
  const denied = tools.execute('write', { path: 'no.txt', content: 'no' });
  tools.approve(events.at(-1).approvalId, false); await assert.rejects(denied, /rejected/);
  await assert.rejects(fs.stat(path.join(workspace, 'no.txt')));
  const accepted = tools.execute('write', { path: 'yes.txt', content: 'yes' });
  const id = events.at(-1).approvalId; tools.approve(id, true); await accepted;
  assert.throws(() => tools.approve(id, true), /expired/);
  const cancelled = tools.execute('write', { path: 'cancel.txt', content: 'x' }); abort.abort(); await assert.rejects(cancelled);
  await assert.rejects(fs.stat(path.join(workspace, 'cancel.txt')));
});
test('symlink and hardlink escapes are rejected', async t => {
  const { tools, root, workspace } = await fixture(t);
  const external = path.join(root, 'external'); await fs.mkdir(external);
  await fs.writeFile(path.join(external, 'secret.txt'), 'private');
  await fs.symlink(external, path.join(workspace, 'link'), process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(tools.execute('read', { path: 'link/secret.txt' }), /Linked/);
  await fs.link(path.join(external, 'secret.txt'), path.join(workspace, 'hard.txt'));
  await assert.rejects(tools.execute('write', { path: 'hard.txt', content: 'overwrite' }), /Linked/);
  assert.equal(await fs.readFile(path.join(external, 'secret.txt'), 'utf8'), 'private');
});
test('shell is disabled by default and unavailable isolation never falls back to host', async t => {
  const disabled = await fixture(t); await assert.rejects(disabled.tools.execute('bash', { command: 'echo nope' }), /disabled/);
  const { tools, workspace } = await fixture(t, { allowShell: true, approvalMode: 'allow' });
  if (!tools.sandbox.available) {
    await assert.rejects(tools.execute('bash', { command: 'echo unsafe > marker.txt' }), /isolation is required/);
    await assert.rejects(fs.stat(path.join(workspace, 'marker.txt')));
  } else {
    const result = await tools.execute('bash', { command: 'echo sandbox-ok; test ! -e /home; test ! -e /etc' });
    assert.match(result.content[0].text, /sandbox-ok/);
  }
});
test('actual pi SDK + loopback model completes write/read/edit tool loop and restores session', { timeout: 60000 }, async t => {
  const { root, workspace } = await fixture(t); const requests = [];
  const actions = [{ name: 'write', arguments: { path: 'hello.txt', content: 'hello pi' } }, { name: 'read', arguments: { path: 'hello.txt' } }, { name: 'edit', arguments: { path: 'hello.txt', oldText: 'pi', newText: 'agent' } }];
  const server = http.createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const request = JSON.parse(raw); requests.push(request);
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
    const index = requests.length - 1, action = actions[index];
    const delta = action ? { role: 'assistant', tool_calls: [{ index: 0, id: `call_${index}`, type: 'function', function: { name: action.name, arguments: JSON.stringify(action.arguments) } }] } : { role: 'assistant', content: 'Completed using real tools.' };
    const chunk = (d, finish = null) => ({ id: `test_${index}`, object: 'chat.completion.chunk', created: 1, model: 'test-model', choices: [{ index: 0, delta: d, finish_reason: finish }] });
    res.write(`data: ${JSON.stringify(chunk(delta))}\n\n`);
    res.write(`data: ${JSON.stringify(chunk({}, action ? 'tool_calls' : 'stop'))}\n\n`); res.end('data: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); t.after(() => server.close());
  async function turn(prompt) {
    const child = spawn(process.execPath, ['src/main.mjs'], { cwd: path.resolve(import.meta.dirname, '..'), env: { ...process.env, CODING_MODEL_API_KEY: 'offline-test-only', CODING_MODEL_ALLOW_PRIVATE: 'true', CODING_MODEL_BASE_URL: `http://127.0.0.1:${server.address().port}/v1`, CODING_MODEL_MODEL: 'test-model' }, windowsHide: true });
    let stderr = ''; child.stderr.on('data', data => { stderr += data; });
    const events = [];
    const lines = readline.createInterface({ input: child.stdout });
    lines.on('line', line => { const event = JSON.parse(line); events.push(event); if (event.type === 'ready') child.stdin.write(JSON.stringify({ id: 'turn', type: 'start', workspace, sessionDir: path.join(root, 'session'), prompt, approvalMode: 'on-risk', allowShell: false }) + '\n'); if (event.event?.type === 'approval_required') child.stdin.write(JSON.stringify({ type: 'approve', id: 'approve', approvalId: event.event.approvalId, approved: true }) + '\n'); if (event.type === 'response' && event.id === 'turn') child.stdin.end(); });
    const code = await new Promise(resolve => child.on('close', resolve));
    assert.equal(code, 0, stderr); assert.equal(events.at(-1).ok, true, JSON.stringify(events));
    return events;
  }
  const events = await turn('Create hello.txt, read it, then change pi to agent.');
  assert.equal(await fs.readFile(path.join(workspace, 'hello.txt'), 'utf8'), 'hello agent');
  assert.equal(events.filter(x => x.event?.type === 'tool_end' && x.event.ok).length, 3);
  assert.equal(requests.length, 4);
  assert.equal(events.filter(x => x.event?.type === 'approval_required').length, 2);
  assert.deepEqual(requests[0].tools.map(x => x.function.name).sort(), ['edit', 'list', 'read', 'write']);
  assert.ok(requests[2].messages.some(x => x.role === 'tool' && x.content.includes('hello pi')));
  await turn('What did you just do?');
  assert.ok(requests.at(-1).messages.some(x => x.role === 'user' && JSON.stringify(x.content).includes('Create hello.txt')));
});



test('cancelling an active command terminates its process tree', { timeout: 10000 }, async t => {
  const { tools, abort } = await fixture(t, { allowShell: true });
  if (!tools.sandbox.available) { await assert.rejects(tools.execute('bash', { command: 'sleep 20' }), /isolation is required/); return; }
  const command = 'sleep 20';
  const running = tools.execute('bash', { command, timeoutSeconds: 30 });
  const timer = setTimeout(() => abort.abort(), 200); t.after(() => clearTimeout(timer));
  await assert.rejects(running, /cancelled/);
});

test('approval expiry emits one resolved event and one failed tool result', async t => {
  const { tools, events, workspace } = await fixture(t, { approvalMode: 'on-risk', approvalTimeoutMs: 10 });
  await assert.rejects(tools.execute('write', { path: 'expired.txt', content: 'x' }, 'expired-call'), /expired/);
  const required = events.find(event => event.type === 'approval_required');
  const resolved = events.filter(event => event.type === 'approval_resolved');
  assert.equal(resolved.length, 1); assert.equal(resolved[0].approvalId, required.approvalId);
  assert.equal(resolved[0].approved, false); assert.equal(resolved[0].reason, 'expired');
  assert.deepEqual(events.at(-1), { type: 'tool_end', toolCallId: 'expired-call', toolName: 'write', ok: false, error: 'Tool operation rejected (expired)', approvalId: required.approvalId });
  assert.throws(() => tools.approve(required.approvalId, true), /expired/);
  await assert.rejects(fs.stat(path.join(workspace, 'expired.txt')));
});
test('80 tool calls is a turn limit that aborts subsequent execution', async t => {
  let context;
  context = await fixture(t, { onLimit: error => context.abort.abort(error) });
  for (let i = 0; i < 80; i++) await context.tools.execute('list', { path: '.' });
  await assert.rejects(context.tools.execute('list', { path: '.' }), /Tool limit reached/);
  assert.equal(context.abort.signal.aborted, true);
  await assert.rejects(context.tools.execute('write', { path: 'after-limit.txt', content: 'no' }), /Tool limit reached/);
  assert.equal(context.events.filter(event => event.type === 'tool_start').length, 80);
});

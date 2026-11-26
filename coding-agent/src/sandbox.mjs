import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { interpretTestRun, normalizeTestTarget, testInvocation } from './repair-verification.mjs';

const MAX_OUTPUT = 65536;
const protectedName = name => /^(\.git|\.ssh|\.aws|\.codex|\.pi|node_modules)$/i.test(name) || /^\.env(?:\.|$)/i.test(name) || /^(credentials|auth|secrets)(?:\.|$)/i.test(name) || /\.(pem|key|p12|pfx)$/i.test(name);
const unavailable = 'Shell unavailable: verified Linux bubblewrap isolation is required; host execution is never used';
let cached;
const bwrapBinary = () => process.env.CODING_BWRAP || '/usr/bin/bwrap';
function childRun(executable, args, { signal, timeoutMs = 30000 } = {}) {
  return new Promise((resolve, reject) => {
    signal?.throwIfAborted();
    const child = spawn(executable, args, { env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' }, windowsHide: true, detached: true, stdio: ['ignore','pipe','pipe'] });
    let output = '', stdout = '', stderr = '', stopped = false;
    const stop = () => { stopped = true; try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); } };
    const timer = setTimeout(stop, timeoutMs);
    signal?.addEventListener('abort', stop, { once: true });
    const append = data => { output += data.toString(); if (Buffer.byteLength(output) > MAX_OUTPUT) { output = output.slice(0, MAX_OUTPUT); stop(); } };
    child.stdout.on('data', data => { if (!stopped) { stdout += data; append(data); } });
    child.stderr.on('data', data => { if (!stopped) { stderr += data; append(data); } });
    const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', stop); };
    child.once('error', () => { cleanup(); reject(new Error(unavailable)); });
    child.once('close', code => { cleanup(); if (stopped) reject(new Error('Isolated command cancelled, timed out or exceeded output limit')); else resolve({ code, output, stdout, stderr }); });
  });
}
async function argsFor(snapshot, command, seconds) {
  const args = ['--as=1073741824', `--cpu=${seconds}`, '--nproc=64', '--nofile=128', '--fsize=16777216', '--', bwrapBinary(),
    '--die-with-parent', '--new-session', '--unshare-all', '--uid', '65534', '--gid', '65534', '--cap-drop', 'ALL', '--clearenv',
    '--setenv', 'PATH', '/usr/bin:/bin', '--setenv', 'HOME', '/tmp', '--setenv', 'LANG', 'C.UTF-8'];
  // Never bind the host root, /home, /etc, credential stores, sockets, or the live workspace.
  for (const mount of ['/usr', '/bin', '/lib', '/lib64']) {
    try { const stat = await fs.lstat(mount); if (stat.isSymbolicLink()) args.push('--symlink', await fs.readlink(mount), mount); else args.push('--ro-bind', mount, mount); } catch {}
  }
  args.push('--tmpfs', '/usr/local', '--proc', '/proc', '--dev', '/dev', '--size', '67108864', '--tmpfs', '/tmp', '--ro-bind', snapshot, '/workspace', '--remount-ro', '/usr/local', '--remount-ro', '/dev', '--remount-ro', '/proc', '--remount-ro', '/', '--chdir', '/workspace', '--', '/bin/sh', '-c', command);
  return args;
}
export async function sandboxStatus({ refresh = false } = {}) {
  const binary=bwrapBinary();
  if (cached && cached.binary===binary && !refresh && Date.now() - cached.at < 30000) return cached.value;
  let value = { available: false, reason: unavailable, backend: 'bubblewrap', network: false, workspace: 'read-only-snapshot' };
  if (!path.isAbsolute(binary) || binary.includes('\0')) return { ...value, reason: 'Shell unavailable: CODING_BWRAP must be an absolute administrator-configured path' };
  if (process.platform === 'linux') {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'coding-sandbox-probe-'));
    try {
      const result = await childRun('/usr/bin/prlimit', await argsFor(root, 'test "$(id -u)" = 65534 && test ! -e /home && test ! -e /etc && test ! -e /sys && test ! -e /run && test -d /workspace && ! touch /root-write 2>/dev/null && ! touch /usr/local/local-write 2>/dev/null && ! touch /dev/dev-write 2>/dev/null && touch /tmp/probe-write && echo isolation-ready', 2), { timeoutMs: 5000 });
      if (result.output.includes('Unknown option')) value={...value,reason:'Shell unavailable: installed bubblewrap lacks required isolation flags; upgrade the administrator-managed backend'};
      if (result.code === 0 && result.output.trim() === 'isolation-ready') value = { ...value, available: true, reason: '' };
    } catch {} finally { await fs.rm(root, { recursive: true, force: true }); }
  }
  cached = { at: Date.now(), binary, value }; return value;
}
async function inspectWorkspace(workspace, {target, testTarget, hash = false}) {
  let bytes = 0, files = 0, entries = 0;
  const digest = hash ? createHash('sha256') : null;
  let targetHash = null;
  const source = await fs.realpath(workspace);
  async function copy(from, to, depth, prefix) {
    if (depth > 20) throw new Error('Sandbox snapshot exceeds directory depth limit');
    const children = await fs.readdir(from, { withFileTypes: true });
    children.sort((a,b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
    for (const entry of children) {
      if (++entries > 2000) throw new Error('Sandbox snapshot exceeds 2000 directory entries');
      if (protectedName(entry.name)) continue;
      const name = prefix + entry.name;
      const src = path.join(from, entry.name), dst = to && path.join(to, entry.name), stat = await fs.lstat(src);
      if (stat.isSymbolicLink() || (!stat.isDirectory() && stat.nlink > 1)) throw new Error('Sandbox snapshot refuses linked paths');
      if (stat.isDirectory()) {
        digest?.update(JSON.stringify(['dir', name]) + '\n');
        if (dst) {
          await fs.mkdir(dst);
          if (hash) await fs.chmod(dst, 0o755);
        }
        await copy(src,dst,depth+1,name+'/');
      }
      else if (stat.isFile()) {
        if (++files > 1000 || (bytes += stat.size) > 32 * 1024 * 1024) throw new Error('Sandbox snapshot exceeds 1000 files or 32 MiB');
        if (dst) {
          await fs.copyFile(src,dst);
          // Some user namespaces cannot map the source uid. The private host
          // parent is 0700 and the sandbox binds this snapshot read-only.
          if (hash) await fs.chmod(dst, 0o444 | (stat.mode & 0o111));
        }
        if (digest) {
          const file = dst || src;
          const contentHash = createHash('sha256').update(await fs.readFile(file)).digest('hex');
          digest.update(JSON.stringify(['file', name, (await fs.stat(file)).mode & 0o111, contentHash]) + '\n');
          if (name === testTarget) targetHash = contentHash;
        }
      } else throw new Error('Sandbox snapshot refuses special files');
    }
  }
  await copy(source, target, 0, '');
  return { files, bytes, ...(hash ? {snapshotHash:digest.digest('hex'),targetHash} : {}) };
}
export function copySafeSnapshot(workspace, target) {
  return inspectWorkspace(workspace, {target});
}
export async function workspaceHash(workspace) {
  return (await inspectWorkspace(workspace, {hash:true})).snapshotHash;
}
export async function runTargetTests({ workspace, runner, target, signal }) {
  target = normalizeTestTarget(runner, target);
  const invocation = testInvocation(runner, target);
  if (!(await sandboxStatus()).available) throw new Error(unavailable);
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'coding-test-'));
  try {
    // Keep the host container private while the isolated uid can traverse the bind mount.
    const snapshot = path.join(directory, 'workspace');
    await fs.mkdir(snapshot, {mode:0o755});
    await fs.chmod(snapshot, 0o755);
    const {snapshotHash,targetHash} = await inspectWorkspace(workspace, {target:snapshot, testTarget:target, hash:true});
    if (!targetHash) throw new Error('Selected test file is missing from the sandbox snapshot');
    const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
    const command = invocation.map(quote).join(' ');
    const result = await childRun('/usr/bin/prlimit', await argsFor(snapshot, command, 60), {signal, timeoutMs:60000});
    return {...interpretTestRun(result), snapshotHash, targetHash};
  } finally {
    await fs.rm(directory, {recursive:true, force:true});
  }
}
export async function runIsolatedCommand({ workspace, command, timeoutSeconds = 30, signal }) {
  if (typeof command !== 'string' || !command.trim() || command.length > 8000) throw new Error('Invalid command');
  if (!(await sandboxStatus()).available) throw new Error(unavailable);
  const seconds = Math.max(1, Math.min(Math.floor(Number(timeoutSeconds) || 30), 120));
  const snapshot = await fs.mkdtemp(path.join(os.tmpdir(), 'coding-sandbox-'));
  try {
    await copySafeSnapshot(workspace, snapshot);
    const result = await childRun('/usr/bin/prlimit', await argsFor(snapshot, command, seconds), { signal, timeoutMs: seconds * 1000 });
    return `Exit code: ${result.code}\n${result.output}`;
  } finally { await fs.rm(snapshot, { recursive: true, force: true }); }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.includes('--check')) { const status = await sandboxStatus({ refresh: true }); process.stdout.write(JSON.stringify(status)+'\n'); process.exitCode=status.available?0:2; }
    else { let raw=''; for await (const data of process.stdin) { raw+=data; if(raw.length>16384)throw new Error('Request too large'); } process.stdout.write(await runIsolatedCommand(JSON.parse(raw))); }
  } catch (error) { process.stderr.write(error.message+'\n'); process.exitCode=2; }
}

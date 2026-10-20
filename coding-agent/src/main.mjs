import readline from 'node:readline';
import { runAgent } from './agent.mjs';
import { sandboxStatus } from './sandbox.mjs';
import { createRedactor } from './model-transport.mjs';

if (process.argv.includes('--check')) {
  try {
    const [major, minor] = process.versions.node.split('.').map(Number);
    if (major < 22 || (major === 22 && minor < 19)) throw new Error('Requires Node >=22.19');
    const sdk = await import('@earendil-works/pi-coding-agent');
    await import('typebox'); await import('@earendil-works/pi-ai');
    if (!sdk.createAgentSession) throw new Error('Incompatible pi SDK');
    process.stdout.write(JSON.stringify({ ok: true, protocolVersion: 1, engine: 'pi', sdkVersion: '1.3.0', node: process.versions.node, sandbox: await sandboxStatus() }) + '\n');
    process.exit(0);
  } catch (error) { process.stdout.write(JSON.stringify({ ok: false, error: error.message }) + '\n'); process.exit(1); }
}
// stdout is an exclusive JSONL protocol channel. SDK diagnostics go to stderr.
const secretRedactor = createRedactor(process.env.CODING_MODEL_API_KEY || '');
const send = value => process.stdout.write(secretRedactor.text(JSON.stringify(value)) + '\n');
const diagnostic = console.error.bind(console);
console.error = (...args) => diagnostic(...args.map(value => secretRedactor.text(value)));
console.log = (...args) => console.error(...args);
const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
const abort = new AbortController();
let active = false, finished = false, tools, session, gate;
const redact = text => String(text).replaceAll(process.env.CODING_MODEL_API_KEY || '\0never', '[redacted]').slice(0, 12000);
const emit = event => send({ type: 'event', event });
input.on('line', line => {
  let command;
  try { if (line.length > 256000) throw new Error('Command too large'); command = JSON.parse(line); }
  catch { send({ type: 'response', ok: false, error: 'Invalid JSON command' }); return; }
  if (command.type === 'model_permission') {
    gate?.permission(command);
  } else if (command.type === 'approve') {
    try { if (typeof command.approved !== 'boolean') throw new Error('approved must be boolean'); tools?.approve(command.approvalId, command.approved); if (!tools) throw new Error('No active approval'); send({ type: 'response', id: command.id, ok: true }); }
    catch (e) { send({ type: 'response', id: command.id, ok: false, error: redact(e.message) }); }
  } else if (command.type === 'cancel') {
    abort.abort(new Error('User cancelled')); tools?.cancel(); void session?.abort();
    send({ type: 'response', id: command.id, ok: true });
  } else if (command.type === 'start' && !active) {
    active = true;
    const timeout = setTimeout(() => abort.abort(new Error('Turn exceeded 10 minute limit')), 600000);
    runAgent(command, { emit, signal: abort.signal, onTools: value => { tools = value; }, onSession: value => { session = value; }, onGate: value => { gate = value; } })
      .then(() => send({ type: 'response', id: command.id, ok: true }))
      .catch(error => { const status = abort.signal.aborted ? 'CANCELLED' : 'FAILED'; emit({ type: 'status', status }); emit({ type: 'error', message: redact(error.message) }); send({ type: 'response', id: command.id, ok: false, error: redact(error.message) }); })
      .finally(() => { finished = true; clearTimeout(timeout); input.close(); process.stdin.pause(); });
  } else send({ type: 'response', id: command.id, ok: false, error: 'Unknown command or turn already started' });
});
input.on('close', () => { if (!finished) abort.abort(new Error('Input connection closed')); });
process.on('SIGTERM', () => abort.abort(new Error('Process terminated')));
process.on('SIGINT', () => abort.abort(new Error('Process interrupted')));
send({ type: 'ready', protocolVersion: 1 });

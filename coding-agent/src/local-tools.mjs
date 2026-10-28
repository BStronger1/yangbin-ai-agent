import { randomUUID } from 'node:crypto';
import { ToolRuntime, readPage } from './tools.mjs';
import { boundedText } from './context-store.mjs';

// File operations are executed by the browser holding a user-granted directory handle.
// The managed server workspace is only a session identity, never a file fallback.
export class LocalToolRuntime extends ToolRuntime {
  constructor(options) {
    super({ ...options, allowShell: false });
    if (this.approvalMode === 'allow') throw new Error('Local file modifications require approval');
    this.requests = new Map();
    this.requestTimeoutMs = options.localTimeoutMs || 30000;
  }
  async init() {
    await super.init();
    this.allowShell = false;
    this.sandbox = { available: false, reason: '本机目录通过浏览器读写，终端未开放' };
    const handshake = await this.exchange('ping', {});
    try { const info = JSON.parse(handshake); this.localProcesses = info.localProcesses === true; this.platform = info.platform; } catch {}
  }
  cancel() {
    super.cancel();
    for (const pending of this.requests?.values() || []) pending.reject(new Error('Local file operation interrupted; inspect files before retrying'));
    this.requests?.clear();
  }
  localResult(result) {
    const pending = this.requests.get(result.requestId);
    if (!pending) return false;
    if (typeof result.ok !== 'boolean' || typeof result.output !== 'string' || Buffer.byteLength(result.output) > 65536) {
      pending.reject(new Error('Invalid local file response')); return false;
    }
    if (result.ok) pending.resolve(result.output);
    else pending.reject(new Error(result.output.slice(0, 500) || 'Local file operation failed'));
    return true;
  }
  exchange(operation, args) {
    this.signal?.throwIfAborted();
    const requestId = randomUUID();
    return new Promise((resolve, reject) => {
      const finish = (fn, value) => { clearTimeout(timer); this.requests.delete(requestId); fn(value); };
      const timer = setTimeout(() => {
        const error = new Error('Local directory disconnected or operation outcome unknown. No automatic replay; inspect files before continuing.');
        finish(reject, error); this.onLimit(error);
      }, this.requestTimeoutMs);
      this.requests.set(requestId, { resolve: value => finish(resolve, value), reject: error => finish(reject, error) });
      try { this.emit({ type: 'local_fs', requestId, operation, arguments: args }); }
      catch (error) { finish(reject, error); }
    });
  }
  async execute(name, args, callId = randomUUID()) {
    if (name === 'recall') return super.execute(name, args, callId);
    try {
      if (!['list', 'read', 'write', 'edit', ...(this.localProcesses ? ['start','preview','process_status','stop'] : [])].includes(name)) throw new Error('Tool unavailable for local directory');
      await this.authorize(name, args, callId, {force: ['start','preview','stop'].includes(name)});
      this.emit({ type: 'tool_start', toolCallId: callId, toolName: name, arguments: args });
      const full = await this.exchange(name, args);
      this.signal?.throwIfAborted();
      const details = {};
      let output = full;
      if (name === 'read') { const page = readPage(full, args); output = page.text; details.page = page.page; }
      if (['read', 'list'].includes(name) && Buffer.byteLength(full) > 2048 && this.contextStore) {
        try { details.contextRef = await this.contextStore.put(full, { kind: name, source: String(args.path || '.').slice(0, 256) }); }
        catch { details.archiveError = 'Snapshot archive unavailable; do not repeat operations just to archive them'; }
      }
      output = boundedText(output, 8192);
      if (details.contextRef) output += '\n[Archived snapshot: recall id=' + details.contextRef.id + '; read for current local file state.]';
      this.emit({ type: 'tool_end', toolCallId: callId, toolName: name, ok: true, output });
      return { content: [{ type: 'text', text: output }], details };
    } catch (error) {
      this.emit({ type: 'tool_end', toolCallId: callId, toolName: name, ok: false, error: error.message });
      throw error;
    }
  }
}

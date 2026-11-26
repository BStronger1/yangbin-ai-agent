import { randomUUID } from 'node:crypto';
import { ToolRuntime, readPage } from './tools.mjs';
import { boundedText } from './context-store.mjs';
import { normalizeTestTarget } from './repair-verification.mjs';
import { mutationReceipt } from './tool-settlement.mjs';

// File operations are executed by the browser holding a user-granted directory handle.
// The managed server workspace is only a session identity, never a file fallback.
export class LocalToolRuntime extends ToolRuntime {
  constructor(options) {
    super({ ...options, allowShell: false });
    this.requests = new Map();
    this.requestTimeoutMs = options.localTimeoutMs || 30000;
  }
  async init() {
    await super.init();
    this.allowShell = false;
    this.sandbox = { available: false, reason: '本机目录通过浏览器读写，终端未开放' };
    await this.requireWorkPermission('ping');
    const handshake = await this.exchange('ping', {});
    try { const info = JSON.parse(handshake); this.localProcesses = info.localProcesses === true; this.platform = info.platform; this.repoTools = info.repoTools === true; this.publicTests = info.publicTests === true; } catch {}
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
    let receipt;
    try {
      receipt = mutationReceipt(result.mutationReceipt);
      if (receipt && (!['write', 'edit'].includes(pending.operation) || (result.ok && receipt.effect !== 'COMMITTED'))) throw new Error('Invalid mutation receipt');
    } catch (error) { pending.reject(error); return false; }
    if (result.ok) pending.resolve(result.output);
    else {
      const error = new Error(result.output.slice(0, 500) || 'Local file operation failed');
      if (receipt) error.mutationReceipt = receipt;
      pending.reject(error);
    }
    return true;
  }
  exchange(operation, args, timeoutMs = this.requestTimeoutMs) {
    this.signal?.throwIfAborted();
    const requestId = randomUUID();
    return new Promise((resolve, reject) => {
      const finish = (fn, value) => { clearTimeout(timer); this.requests.delete(requestId); fn(value); };
      const timer = setTimeout(() => {
        const error = new Error('Local directory disconnected or operation outcome unknown. No automatic replay; inspect files before continuing.');
        finish(reject, error); this.onLimit(error);
      }, timeoutMs);
      this.requests.set(requestId, { operation, resolve: value => finish(resolve, value), reject: error => finish(reject, error) });
      try { this.emit({ type: 'local_fs', requestId, operation, arguments: args }); }
      catch (error) { finish(reject, error); }
    });
  }
  async perform(name, args, callId = randomUUID()) {
    if (name === 'recall') return super.perform(name, args, callId);
    const checking = name === 'verify_tests' && this.publicTests && this.verification;
    const generation = checking ? this.verification.beginCheck() : null;
    let checkDispatched = false;
    let mutationFinished = false;
    try {
      if (!['list', 'read', 'write', 'edit', ...(this.repoTools ? ['search'] : []), ...(checking ? ['verify_tests'] : []), ...(this.localProcesses ? ['start','preview','process_status','stop'] : [])].includes(name)) throw new Error('Tool unavailable for local directory');
      if (checking) args = {runner:args.runner,target:normalizeTestTarget(args.runner,args.target)};
      await this.authorize(name, args, callId, {force: this.approvalMode !== 'allow' && ['start','preview','stop'].includes(name)});
      this.emit({ type: 'tool_start', toolCallId: callId, toolName: name, arguments: args });
      if (['write','edit','start','preview','stop'].includes(name)) this.dispatched.add(callId);
      checkDispatched = checking;
      const full = await this.exchange(name, name === 'read' && this.repoTools ? {...args, paged:true} : args, checking ? 125000 : this.requestTimeoutMs);
      mutationFinished = ['write','edit'].includes(name);
      this.signal?.throwIfAborted();
      const details = {};
      let output = full;
      if (checking) {
        const result = JSON.parse(full);
        if (!['PASSED','FAILED','NO_TESTS','BLOCKED'].includes(result.status) ||
            !/^[0-9a-f]{64}$/.test(result.snapshotHash) || !/^[0-9a-f]{64}$/.test(result.targetHash) ||
            !Number.isInteger(result.exitCode) || typeof result.diagnostic !== 'string' ||
            (['PASSED','FAILED'].includes(result.status) && (!Number.isSafeInteger(result.testsRun) || result.testsRun <= 0))) throw new Error('Invalid native test evidence');
        details.verification = this.verification.checked(generation,args.runner,args.target,result);
        output = JSON.stringify(details.verification) + '\n' + result.diagnostic;
      }
      if (name === 'read') { const page = this.repoTools ? JSON.parse(full) : readPage(full, args); output = page.text; details.page = page.page; }
      if (['read', 'list'].includes(name) && Buffer.byteLength(full) > 2048 && this.contextStore) {
        try { details.contextRef = await this.contextStore.put(full, { kind: name, source: String(args.path || '.').slice(0, 256) }); }
        catch { details.archiveError = 'Snapshot archive unavailable; do not repeat operations just to archive them'; }
      }
      output = boundedText(output, 8192);
      if (details.contextRef) output += '\n[Archived snapshot: recall id=' + details.contextRef.id + '; read for current local file state.]';
      this.emit({ type: 'tool_end', toolCallId: callId, toolName: name, ok: true, output });
      return { content: [{ type: 'text', text: output }], details };
    } catch (error) {
      if (mutationFinished) error.mutationReceipt = {effect:'COMMITTED',phase:'COMMITTED'};
      if (checkDispatched && !this.signal?.aborted) this.verification.checked(generation,args.runner,args.target,{status:'BLOCKED',snapshotHash:null,testsRun:null,exitCode:null});
      this.emit({ type: 'tool_end', toolCallId: callId, toolName: name, ok: false, error: error.message });
      throw error;
    }
  }
}

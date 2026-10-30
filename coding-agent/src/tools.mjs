import { FileOperations, atomicReplace, fileHash } from './file-operations.mjs';
import fs from 'node:fs/promises';
import path from 'node:path';
import { sandboxStatus, runIsolatedCommand } from './sandbox.mjs';
import { randomUUID } from 'node:crypto';
import { boundedText } from './context-store.mjs';

const LIMIT = 64 * 1024;
const protectedPart = part => /^\.coding-write-/i.test(part) || /^(\.git|\.ssh|\.aws|\.codex|\.pi|node_modules)$/i.test(part) || /^\.env(?:\.|$)/i.test(part) || /^(credentials|auth|secrets)(?:\.|$)/i.test(part) || /\.(pem|key|p12|pfx)$/i.test(part);
export class ToolRuntime {
  constructor({ workspace, approvalMode = 'on-risk', allowShell = false, emit = () => {}, signal, contextStore, onLimit = () => {}, approvalTimeoutMs = 300000, execution, fileJournal, operationCheckpoint }) {
    if (!['ask', 'on-risk', 'allow'].includes(approvalMode)) throw new Error('Invalid approval mode');
    this.workspace = path.resolve(workspace); this.approvalMode = approvalMode;
    this.contextStore = contextStore; this.allowShell = allowShell === true; this.emit = emit; this.signal = signal;
    this.fileOperations = fileJournal || (execution?.fileJournalDir ? new FileOperations({directory:execution.fileJournalDir,identity:execution.identity,checkpoint:operationCheckpoint}) : null);
    this.pending = new Map(); this.count = 0; this.onLimit = onLimit; this.approvalTimeoutMs = Math.max(1, Math.min(approvalTimeoutMs, 300000));
    signal?.addEventListener('abort', () => this.cancel(), { once: true });
  }
  async init() { this.workspace = await fs.realpath(this.workspace); if(this.fileOperations && (this.fileOperations.directory===this.workspace || this.fileOperations.directory.startsWith(this.workspace+path.sep)))throw Error('Operation journal must be outside workspace'); this.sandbox = await sandboxStatus(); this.shellRequested = this.allowShell; this.allowShell = this.allowShell && this.sandbox.available; }
  async resolve(input, allowMissing = false) {
    if (typeof input !== 'string' || !input || input.includes('\0')) throw new Error('A relative workspace path is required');
    if (path.isAbsolute(input) || /^[a-zA-Z]:/.test(input) || input.includes(':') || input.includes('\\')) throw new Error('Only relative paths with / separators are allowed');
    const parts = input.split('/').filter(x => x && x !== '.');
    if (parts.some(x => x === '..' || /[. ]$/.test(x) || /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i.test(x) || protectedPart(x))) throw new Error('Protected path or workspace escape rejected');
    const result = path.resolve(this.workspace, ...parts);
    if (result !== this.workspace && !result.startsWith(this.workspace + path.sep)) throw new Error('Workspace escape rejected');
    let current = this.workspace;
    for (const part of parts) {
      current = path.join(current, part);
      try {
        const stat = await fs.lstat(current);
        if (stat.isSymbolicLink() || (!stat.isDirectory() && stat.nlink > 1)) throw new Error('Linked paths are not allowed');
        const real = await fs.realpath(current);
        if (real !== this.workspace && !real.startsWith(this.workspace + path.sep)) throw new Error('Workspace escape rejected');
      } catch (e) { if (!(allowMissing && e.code === 'ENOENT')) throw e; }
    }
    return result;
  }
  cancel() { for (const waiter of this.pending.values()) waiter(false, 'cancelled'); this.pending.clear(); }
  approve(id, approved) { const waiter = this.pending.get(id); if (!waiter) throw new Error('Approval expired or unknown'); waiter(approved === true, approved === true ? 'approved' : 'rejected'); }
  async authorize(name, args, callId, {force=false}={}) {
    this.signal?.throwIfAborted();
    if (++this.count > 80) { const error = new Error('Tool limit reached (80 per turn)'); this.onLimit(error); throw error; }
    if (name === 'bash' && !this.allowShell) throw new Error(this.shellRequested ? this.sandbox.reason : 'Shell execution is disabled');
    if (!force && (this.approvalMode === 'allow' || (this.approvalMode === 'on-risk' && !['write', 'edit', 'bash'].includes(name)))) return;
    const approvalId = randomUUID();
    const reason = name === 'bash' ? '在禁网隔离环境的只读工作区快照中运行验证命令；更改不会写回工作区。' : '当前模式要求确认本次工具操作。';
    const resolution = await new Promise(resolve => {
      const settle = (approved, resolutionReason) => {
        if (!this.pending.has(approvalId)) return;
        clearTimeout(timer); this.pending.delete(approvalId);
        this.emit({ type: 'approval_resolved', approvalId, toolCallId: callId, toolName: name, approved, reason: resolutionReason });
        resolve({ approved, reason: resolutionReason });
      };
      const timer = setTimeout(() => settle(false, 'expired'), this.approvalTimeoutMs);
      this.pending.set(approvalId, settle);
      this.emit({ type: 'approval_required', approvalId, toolCallId: callId, toolName: name, arguments: args, reason });
    });
    if (!resolution.approved) {
      const error = new Error(`Tool operation rejected (${resolution.reason})`);
      error.approvalId = approvalId; throw error;
    }
    this.signal?.throwIfAborted();
  }
  async execute(name, args, callId = randomUUID()) {
    try {
      await this.authorize(name, args, callId);
      this.emit({ type: 'tool_start', toolCallId: callId, toolName: name, arguments: args });
      let output; let fullOutput; let details = {};
      this.signal?.throwIfAborted();
      if (name === 'list') {
        const location = await this.resolve(args.path || '.');
        const entries = await fs.readdir(location, { withFileTypes: true });
        const visible = entries.filter(x => !protectedPart(x.name) && !x.isSymbolicLink());
        output = visible.slice(0, 1000).map(x => `${x.isDirectory() ? 'dir ' : 'file'} ${x.name}`).join('\n');
        if (visible.length > 1000) output += '\n[Directory listing limited to 1000 entries; narrow the path to inspect subdirectories.]';
      } else if (name === 'read') {
        const location = await this.resolve(args.path);
        const stat = await fs.stat(location);
        if (!stat.isFile() || stat.size > LIMIT) throw new Error('Read requires a text file of at most 64 KiB');
        output = await fs.readFile(location, 'utf8');
        if (output.includes('\0')) throw new Error('Binary files are not supported');
        fullOutput = output;
        const page = readPage(output, args); output = page.text; details.page = page.page;
      } else if (name === 'recall') {
        if (!this.contextStore) throw new Error('Context recall is unavailable for this session');
        const result = args.id ? await this.contextStore.read(args) : await this.contextStore.list(args);
        if (args.id) {
          const text = boundedText(result.text, 7000);
          result.nextOffset = result.offset + text.length < result.totalChars ? result.offset + text.length : null;
          output = text + '\n[Archived snapshot id=' + result.id + ' offset=' + result.offset + ' nextOffset=' + result.nextOffset + ' totalChars=' + result.totalChars + ']';
        } else {
          while (result.entries.length > 1 && Buffer.byteLength(JSON.stringify(result)) > 7600) { result.entries.pop(); result.nextOffset = (args.offset || 0) + result.entries.length; }
          output = JSON.stringify(result);
        }
        details.recall = { id: args.id ?? null, nextOffset: result.nextOffset };
      } else if (name === 'write' || name === 'edit') {
        const location = await this.resolve(args.path, name === 'write');
        if (location === this.workspace) throw new Error('Cannot replace workspace root');
        const produce = async () => {
        let content = args.content;
        if (name === 'edit') {
          const stat = await fs.stat(location);
          if (!stat.isFile() || stat.size > LIMIT) throw new Error('Edit requires a text file of at most 64 KiB');
          const original = await fs.readFile(location, 'utf8');
          if (typeof args.oldText !== 'string' || !args.oldText || typeof args.newText !== 'string') throw new Error('oldText and newText are required');
          const index = original.indexOf(args.oldText);
          if (index < 0 || original.indexOf(args.oldText, index + 1) >= 0) throw new Error('oldText must match exactly once');
          content = original.slice(0, index) + args.newText + original.slice(index + args.oldText.length);
        }
        if (typeof content !== 'string' || Buffer.byteLength(content) > LIMIT || content.includes('\0')) throw new Error('Content must be text of at most 64 KiB');
        return content;
        };
        const resolve = () => this.resolve(args.path, true);
        if(this.fileOperations) output = await this.fileOperations.mutate({name,args:{...args,path:path.relative(this.workspace,location).split(path.sep).join('/')},callId,location,content:produce,resolve,signal:this.signal});
        else {
          const beforeHash=await fileHash(location),content=await produce();
          await atomicReplace({location,content,beforeHash,resolve,signal:this.signal});
          output=`${name === 'edit' ? 'Edited' : 'Wrote'} ${args.path} (${Buffer.byteLength(content)} bytes)`;
        }
      } else if (name === 'bash') output = await this.runCommand(args);
      else throw new Error('Unknown tool');
      this.signal?.throwIfAborted();
      if (['read', 'list', 'bash'].includes(name)) {
        fullOutput ??= String(output);
        // Preserve the pre-existing tool's bounded output; never claim unlimited capture.
        const bounded = boundedText(fullOutput, LIMIT);
        const sourceTruncated = bounded.length < fullOutput.length;
        fullOutput = bounded;
        if (Buffer.byteLength(fullOutput) > 2048 && this.contextStore) {
          try {
            details.contextRef = await this.contextStore.put(fullOutput, { kind: name, source: name === 'bash' ? 'isolated command output' : String(args.path || '.').slice(0, 256) });
          } catch (error) {
            this.signal?.throwIfAborted();
            // Execution already happened. Do not turn a capture failure into an invitation to repeat it.
            details.archiveError = 'Context archive unavailable';
            output = boundedText(String(output)) + '\n[Archive failed: result was produced but is NOT recoverable from recall. Do not repeat commands merely to archive them.]';
          }
        }
        const visible = boundedText(String(output));
        if (visible.length < String(output).length && !details.archiveError) output = visible + '\n[Output page shortened to 8 KiB.]';
        else if (!details.archiveError) output = String(output);
        if (details.contextRef) output += '\n[Archived snapshot: recall id=' + details.contextRef.id + '; offsets are UTF-16 characters. Source may since have changed.]';
        else if (Buffer.byteLength(fullOutput) > 8192 && name !== 'read' && !details.archiveError) output += '\n[No archive is available; omitted output cannot be recalled.]';
        if (sourceTruncated) output += '\n[Original capture limited to 64 KiB; content beyond this was not archived.]';
      }
      this.signal?.throwIfAborted();
      this.emit({ type: 'tool_end', toolCallId: callId, toolName: name, ok: true, output: output.slice(0, 12000) });
      return { content: [{ type: 'text', text: output }], details };
    } catch (error) {
      if(error.recoveryRequired)this.onLimit(error);
      this.emit({ type: 'tool_end', toolCallId: callId, toolName: name, ok: false, error: error.message, ...(error.approvalId ? { approvalId: error.approvalId } : {}) });
      throw error;
    }
  }
  async runCommand(args) {
    if (!this.allowShell) throw new Error(this.sandbox?.reason || 'Shell execution is disabled');
    return runIsolatedCommand({ workspace: this.workspace, ...args, signal: this.signal });
  }
}

export function readPage(text, args) {
  const offset = args.offset ?? 0, limit = args.limit ?? 2000, charOffset = args.charOffset ?? 0;
  if (![offset, limit, charOffset].every(Number.isSafeInteger) || offset < 0 || limit < 1 || limit > 2000 || charOffset < 0) throw new Error('Invalid read pagination: offset/limit are lines; charOffset is within the first line');
  const lines = text.match(/[^\n]*\n|[^\n]+$/g) || [];
  if (offset > lines.length || (offset === lines.length && charOffset) || (offset < lines.length && charOffset > lines[offset].length)) throw new Error('Read offset outside file');
  const selected = lines.slice(offset, offset + limit).join('').slice(charOffset);
  const value = boundedText(selected, 7800);
  let nextLine = offset, nextChar = charOffset + value.length;
  while (nextLine < lines.length && nextChar >= lines[nextLine].length) { nextChar -= lines[nextLine].length; nextLine++; }
  const more = nextLine < lines.length;
  const page = { offset, charOffset, totalLines: lines.length, nextOffset: more ? nextLine : null, nextCharOffset: more ? nextChar : null };
  const footer = more ? '\n[More content: read offset=' + nextLine + ' charOffset=' + nextChar + '; offset is a zero-based line index.]' : '';
  return { text: value + footer, page };
}

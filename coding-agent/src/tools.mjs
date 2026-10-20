import { constants } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';
import { sandboxStatus, runIsolatedCommand } from './sandbox.mjs';
import { randomUUID } from 'node:crypto';

const LIMIT = 64 * 1024;
const protectedPart = part => /^(\.git|\.ssh|\.aws|\.codex|\.pi|node_modules)$/i.test(part) || /^\.env(?:\.|$)/i.test(part) || /^(credentials|auth|secrets)(?:\.|$)/i.test(part) || /\.(pem|key|p12|pfx)$/i.test(part);
export class ToolRuntime {
  constructor({ workspace, approvalMode = 'on-risk', allowShell = false, emit = () => {}, signal, onLimit = () => {}, approvalTimeoutMs = 300000 }) {
    if (!['ask', 'on-risk', 'allow'].includes(approvalMode)) throw new Error('Invalid approval mode');
    this.workspace = path.resolve(workspace); this.approvalMode = approvalMode;
    this.allowShell = allowShell === true; this.emit = emit; this.signal = signal;
    this.pending = new Map(); this.count = 0; this.onLimit = onLimit; this.approvalTimeoutMs = Math.max(1, Math.min(approvalTimeoutMs, 300000));
    signal?.addEventListener('abort', () => this.cancel(), { once: true });
  }
  async init() { this.workspace = await fs.realpath(this.workspace); this.sandbox = await sandboxStatus(); this.shellRequested = this.allowShell; this.allowShell = this.allowShell && this.sandbox.available; }
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
  async authorize(name, args, callId) {
    this.signal?.throwIfAborted();
    if (++this.count > 80) { const error = new Error('Tool limit reached (80 per turn)'); this.onLimit(error); throw error; }
    if (name === 'bash' && !this.allowShell) throw new Error(this.shellRequested ? this.sandbox.reason : 'Shell execution is disabled');
    if (this.approvalMode === 'allow' || (this.approvalMode === 'on-risk' && !['write', 'edit', 'bash'].includes(name))) return;
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
      let output;
      if (name === 'list') {
        const location = await this.resolve(args.path || '.');
        const entries = await fs.readdir(location, { withFileTypes: true });
        output = entries.filter(x => !protectedPart(x.name) && !x.isSymbolicLink()).slice(0, 1000).map(x => `${x.isDirectory() ? 'dir ' : 'file'} ${x.name}`).join('\n');
      } else if (name === 'read') {
        const location = await this.resolve(args.path);
        const stat = await fs.stat(location);
        if (!stat.isFile() || stat.size > LIMIT) throw new Error('Read requires a text file of at most 64 KiB');
        output = await fs.readFile(location, 'utf8');
        if (output.includes('\0')) throw new Error('Binary files are not supported');
      } else if (name === 'write' || name === 'edit') {
        const location = await this.resolve(args.path, name === 'write');
        if (location === this.workspace) throw new Error('Cannot replace workspace root');
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
        await fs.mkdir(path.dirname(location), { recursive: true });
        await this.resolve(args.path, true);
        const handle = await fs.open(location, constants.O_WRONLY | constants.O_CREAT | constants.O_TRUNC | (constants.O_NOFOLLOW || 0), 0o600);
        try { await handle.writeFile(content, 'utf8'); } finally { await handle.close(); }
        output = `${name === 'edit' ? 'Edited' : 'Wrote'} ${args.path} (${Buffer.byteLength(content)} bytes)`;
      } else if (name === 'bash') output = await this.runCommand(args);
      else throw new Error('Unknown tool');
      output = String(output).slice(0, LIMIT);
      this.emit({ type: 'tool_end', toolCallId: callId, toolName: name, ok: true, output: output.slice(0, 12000) });
      return { content: [{ type: 'text', text: output }], details: {} };
    } catch (error) {
      this.emit({ type: 'tool_end', toolCallId: callId, toolName: name, ok: false, error: error.message, ...(error.approvalId ? { approvalId: error.approvalId } : {}) });
      throw error;
    }
  }
  async runCommand(args) {
    if (!this.allowShell) throw new Error(this.sandbox?.reason || 'Shell execution is disabled');
    return runIsolatedCommand({ workspace: this.workspace, ...args, signal: this.signal });
  }
}

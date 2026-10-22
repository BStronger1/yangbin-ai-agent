import fs from 'node:fs/promises';
import { constants } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { randomUUID, createHash } from 'node:crypto';

export const CONTEXT_LIMITS = Object.freeze({ itemBytes: 256 * 1024, totalBytes: 8 * 1024 * 1024, items: 512, pageBytes: 8192 });
const sessionQueues = new Map();
function serialized(directory, action) {
  const operation = (sessionQueues.get(directory) || Promise.resolve()).catch(() => {}).then(action);
  sessionQueues.set(directory, operation);
  operation.finally(() => { if (sessionQueues.get(directory) === operation) sessionQueues.delete(directory); }).catch(() => {});
  return operation;
}
const sameFile = (a, b) => a.dev === b.dev && a.ino === b.ino;
const ID = /^[a-f0-9]{32}$/;
const digest = text => createHash('sha256').update(text, 'utf8').digest('hex');
const integer = (value, fallback, maximum) => {
  value ??= fallback;
  if (!Number.isSafeInteger(value) || value < 0 || value > maximum) throw new Error('Invalid context pagination');
  return value;
};
export function boundedText(text, maxBytes = CONTEXT_LIMITS.pageBytes) {
  if (Buffer.byteLength(text) <= maxBytes) return text;
  let low = 0, high = Math.min(text.length, maxBytes);
  while (low < high) { const middle = Math.ceil((low + high) / 2); if (Buffer.byteLength(text.slice(0, middle)) <= maxBytes) low = middle; else high = middle - 1; }
  if (low && /[\uD800-\uDBFF]/.test(text[low - 1])) low--;
  return text.slice(0, low);
}

// The server selects the session directory; model-supplied paths are never accepted.
export class ContextStore {
  constructor({ sessionDir, signal, redact = text => text }) {
    if (typeof sessionDir !== 'string' || !sessionDir || sessionDir.includes('\0')) throw new Error('A session directory is required');
    this.sessionDir = path.resolve(sessionDir); this.directory = path.join(this.sessionDir, 'context-artifacts');
    this.signal = signal; this.redact = redact; this.ready = false; this.queue = Promise.resolve();
  }
  async checkDirectory(allowMissing = false) {
    this.signal?.throwIfAborted();
    const parsed = path.parse(this.directory); let current = parsed.root;
    for (const part of this.directory.slice(parsed.root.length).split(path.sep).filter(Boolean)) {
      current = path.join(current, part);
      try { const stat = await fs.lstat(current); if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error('Linked or invalid context directory rejected'); }
      catch (error) { if (!(allowMissing && error.code === 'ENOENT')) throw error; }
    }
  }
  async init() {
    await this.checkDirectory(true); await fs.mkdir(this.directory, { recursive: true, mode: 0o700 });
    await serialized(this.directory, async () => { await this.checkDirectory(); await this.recoverLocks(); await this.scan(); }); this.ready = true; return this;
  }
  async record(id) {
    if (typeof id !== 'string' || !ID.test(id)) throw new Error('Invalid context reference');
    const location = path.join(this.directory, id + '.json');
    const stat = await fs.lstat(location);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1 || stat.size > CONTEXT_LIMITS.itemBytes * 6 + 4096) throw new Error('Linked or oversized context record rejected');
    const handle = await fs.open(location, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
    let record;
    try {
      const opened = await handle.stat();
      if (!opened.isFile() || opened.nlink !== 1 || opened.ino !== stat.ino || opened.dev !== stat.dev || opened.size !== stat.size) throw new Error('Context record changed during read');
      record = JSON.parse(await handle.readFile('utf8'));
    } finally { await handle.close(); }
    if (record.version !== 1 || record.id !== id || typeof record.text !== 'string' || typeof record.kind !== 'string' || record.kind.length > 64 || typeof record.source !== 'string' || record.source.length > 256 || typeof record.createdAt !== 'string' || !Number.isFinite(Date.parse(record.createdAt)) || record.bytes !== Buffer.byteLength(record.text) || record.bytes > CONTEXT_LIMITS.itemBytes || record.sha256 !== digest(record.text)) throw new Error('Corrupt context record rejected');
    return record;
  }
  async scan() {
    const records = []; let bytes = 0;
    const directory = await fs.opendir(this.directory);
    for await (const item of directory) {
      this.signal?.throwIfAborted();
      if (['.write-lock', '.recovering-lock'].includes(item.name) && item.isDirectory()) continue;
      if (!/^[a-f0-9]{32}\.json$/.test(item.name) || !item.isFile() || records.length >= CONTEXT_LIMITS.items) throw new Error('Invalid context archive entry or count quota exceeded');
      const record = await this.record(item.name.slice(0, -5)); records.push(record); bytes += record.bytes;
      if (bytes > CONTEXT_LIMITS.totalBytes) throw new Error('Context archive byte quota exceeded');
    }
    records.sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id));
    return { records, bytes };
  }
  metadata(record) { const { id, bytes, kind, source, createdAt } = record; return { id, bytes, kind, source, createdAt, totalChars: record.text.length }; }
  async ensure() { if (!this.ready) throw new Error('Context store is not initialized'); await this.checkDirectory(); }
  put(text, { kind = 'tool', source = '' } = {}) {
    return serialized(this.directory, () => this.write(text, { kind, source }));
  }
  async write(text, { kind, source }) {
    await this.ensure();
    if (typeof text !== 'string' || Buffer.byteLength(text) > CONTEXT_LIMITS.itemBytes || typeof kind !== 'string' || kind.length > 64 || typeof source !== 'string' || source.length > 256) throw new Error('Context item exceeds bounds');
    text = this.redact(text); source = this.redact(source); kind = this.redact(kind);
    if (typeof text !== 'string' || typeof source !== 'string' || typeof kind !== 'string' || source.length > 256 || kind.length > 64 || Buffer.byteLength(text) > CONTEXT_LIMITS.itemBytes) throw new Error('Invalid redacted context item');
    await this.recoverLocks();
    const lock = path.join(this.directory, '.write-lock');
    try { await fs.mkdir(lock, { mode: 0o700 }); } catch (error) { if (error.code === 'EEXIST') throw new Error('Context store is busy or requires lock recovery'); throw error; }
    const lockStat = await fs.lstat(lock);
    const owner = { pid: process.pid, hostname: os.hostname(), token: randomUUID().replaceAll('-', '') };
    let temporary;
    try {
      const ownerHandle = await fs.open(path.join(lock, 'owner.json'), 'wx', 0o600);
      try { await ownerHandle.writeFile(JSON.stringify(owner)); await ownerHandle.sync(); } finally { await ownerHandle.close(); }
      await this.checkDirectory(); const { records, bytes } = await this.scan();
      const sha256 = digest(text), size = Buffer.byteLength(text);
      const existing = records.find(record => record.sha256 === sha256 && record.kind === kind && record.source === source);
      if (existing) return this.metadata(existing);
      if (records.length >= CONTEXT_LIMITS.items || bytes + size > CONTEXT_LIMITS.totalBytes) throw new Error('Context archive quota exceeded; existing records were retained');
      const id = owner.token;
      const record = { version: 1, id, kind, source, createdAt: new Date().toISOString(), bytes: size, sha256, text };
      temporary = path.join(lock, id + '.pending');
      const handle = await fs.open(temporary, 'wx', 0o600);
      try { await handle.writeFile(JSON.stringify(record), 'utf8'); await handle.sync(); } finally { await handle.close(); }
      this.signal?.throwIfAborted(); await this.checkDirectory();
      await fs.rename(temporary, path.join(this.directory, id + '.json')); temporary = undefined;
      if (process.platform !== 'win32') { const directory = await fs.open(this.directory, constants.O_RDONLY); try { await directory.sync(); } finally { await directory.close(); } }
      return this.metadata(record);
    } finally {
      // Only clean this writer's own lock; a moved/replaced lock is left for verified recovery.
      const current = await fs.lstat(lock).catch(error => { if (error.code !== 'ENOENT') throw error; return null; });
      if (current && !current.isSymbolicLink() && sameFile(current, lockStat)) {
        if (temporary) await fs.unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; });
        await fs.unlink(path.join(lock, 'owner.json')).catch(error => { if (error.code !== 'ENOENT') throw error; });
        await fs.rmdir(lock);
      }
    }
  }

  async inspectLock(location) {
    const stat = await fs.lstat(location);
    if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error('Linked context lock rejected');
    const entries = await fs.readdir(location);
    if (entries.length > 2 || !entries.includes('owner.json')) throw new Error('Context lock owner missing or unknown lock files; manual recovery required');
    const ownerPath = path.join(location, 'owner.json'), ownerStat = await fs.lstat(ownerPath);
    if (!ownerStat.isFile() || ownerStat.isSymbolicLink() || ownerStat.nlink !== 1 || ownerStat.size > 4096) throw new Error('Invalid context lock owner');
    const handle = await fs.open(ownerPath, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
    let owner;
    try { const opened = await handle.stat(); if (!sameFile(opened, ownerStat) || opened.nlink !== 1 || opened.size !== ownerStat.size) throw new Error('Context lock owner changed'); owner = JSON.parse(await handle.readFile('utf8')); }
    finally { await handle.close(); }
    if (!Number.isSafeInteger(owner.pid) || owner.pid < 1 || owner.pid > 2147483647 || typeof owner.hostname !== 'string' || owner.hostname.length > 255 || typeof owner.token !== 'string' || !ID.test(owner.token)) throw new Error('Invalid context lock owner');
    const pendingName = owner.token + '.pending';
    for (const entry of entries) {
      if (entry === 'owner.json') continue;
      if (entry !== pendingName) throw new Error('Unknown context lock file; manual recovery required');
      const pending = await fs.lstat(path.join(location, entry));
      if (!pending.isFile() || pending.isSymbolicLink() || pending.nlink !== 1 || pending.size > CONTEXT_LIMITS.itemBytes * 6 + 4096) throw new Error('Linked or oversized orphan context pending file rejected');
    }
    return { stat, owner, pending: entries.includes(pendingName) ? pendingName : null };
  }
  ownerIsDead(owner) {
    if (owner.hostname !== os.hostname()) throw new Error('Context lock belongs to another host; manual recovery required');
    try { process.kill(owner.pid, 0); return false; }
    catch (error) { if (error.code === 'ESRCH') return true; throw new Error('Cannot verify context lock owner; manual recovery required'); }
  }
  async cleanDeadLock(location, expected) {
    const current = await this.inspectLock(location);
    if (!sameFile(current.stat, expected.stat) || current.owner.token !== expected.owner.token || !this.ownerIsDead(current.owner)) throw new Error('Context lock changed or owner is active; recovery stopped');
    if (current.pending) await fs.unlink(path.join(location, current.pending));
    // Recheck ownership before deleting the last identifying file.
    const final = await this.inspectLock(location);
    if (!sameFile(final.stat, current.stat) || final.owner.token !== current.owner.token || !this.ownerIsDead(final.owner)) throw new Error('Context lock changed during recovery');
    await fs.unlink(path.join(location, 'owner.json'));
    await fs.rmdir(location);
  }
  async recoverLocks() {
    await this.checkDirectory();
    const recovering = path.join(this.directory, '.recovering-lock');
    const recoveryStat = await fs.lstat(recovering).catch(error => { if (error.code !== 'ENOENT') throw error; return null; });
    if (recoveryStat) {
      if (recoveryStat.isSymbolicLink() || !recoveryStat.isDirectory()) throw new Error('Linked context recovery directory rejected');
      // A crash between removing owner.json and rmdir leaves an empty quarantine.
      if ((await fs.readdir(recovering)).length === 0) await fs.rmdir(recovering);
      else { const old = await this.inspectLock(recovering); if (!this.ownerIsDead(old.owner)) throw new Error('Context recovery owner is active'); await this.cleanDeadLock(recovering, old); }
    }
    const lock = path.join(this.directory, '.write-lock');
    const stat = await fs.lstat(lock).catch(error => { if (error.code !== 'ENOENT') throw error; return null; });
    if (!stat) return;
    const old = await this.inspectLock(lock);
    if (!this.ownerIsDead(old.owner)) return;
    // One bounded quarantine, never an accumulating list of directories. Revalidate the
    // inode and token after rename; a racing live writer must never be deleted.
    await fs.rename(lock, recovering);
    const moved = await this.inspectLock(recovering);
    if (!sameFile(moved.stat, old.stat) || moved.owner.token !== old.owner.token) throw new Error('Context lock changed during quarantine; preserved for manual recovery');
    await this.cleanDeadLock(recovering, moved);
  }

  async read({ id, offset, limit } = {}) {
    await this.ensure(); offset = integer(offset, 0, Number.MAX_SAFE_INTEGER); limit = integer(limit, 8192, 8192);
    if (limit === 0) throw new Error('Context page limit must be positive');
    const record = await this.record(id);
    if (offset > record.text.length) throw new Error('Context offset is outside the record');
    if (offset > 0 && /[\uD800-\uDBFF]/.test(record.text[offset - 1]) && /[\uDC00-\uDFFF]/.test(record.text[offset] || '')) throw new Error('Context offset cannot split a Unicode character');
    let end = Math.min(record.text.length, offset + limit);
    if (end < record.text.length && /[\uD800-\uDBFF]/.test(record.text[end - 1])) end--;
    const text = boundedText(record.text.slice(offset, end));
    if (!text && offset < record.text.length) throw new Error('Context page limit cannot split a Unicode character');
    return { ...this.metadata(record), text, offset, nextOffset: offset + text.length < record.text.length ? offset + text.length : null };
  }
  async list({ offset, limit } = {}) {
    await this.ensure(); offset = integer(offset, 0, CONTEXT_LIMITS.items); limit = integer(limit, 20, 50);
    if (!limit) throw new Error('Context list limit must be positive');
    const { records, bytes } = await this.scan();
    return { entries: records.slice(offset, offset + limit).map(record => this.metadata(record)), total: records.length, bytes, nextOffset: offset + limit < records.length ? offset + limit : null };
  }
}


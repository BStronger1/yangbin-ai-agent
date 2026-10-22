// Direct File System Access handles stay on this device and are scoped to the signed-in account.
const LIMIT = 65536;
const denied = part => /^(\.git|\.ssh|\.aws|\.codex|\.pi|node_modules)$/i.test(part) || /^\.env(?:\.|$)/i.test(part) || /^(credentials|auth|secrets)(?:\.|$)/i.test(part) || /\.(pem|key|p12|pfx)$/i.test(part);
export function localPath(value, directory = false) {
  if (directory && (value === '.' || value === '' || value == null)) return [];
  if (typeof value !== 'string' || !value || value.length > 500 || /[\\:\x00-\x1f]/.test(value)) throw new Error('仅允许项目内相对路径');
  const parts = value.split('/');
  if (parts.some(p => !p || p === '.' || p === '..' || /[. ]$/.test(p) || /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i.test(p) || denied(p))) throw new Error('路径越界或属于受保护文件');
  if (parts.length > 20) throw new Error('文件路径过深');
  return parts;
}
function textOnly(text) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > LIMIT || /[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(text)) throw new Error('仅支持64KiB以内的UTF-8文本文件');
  return text;
}
async function fileText(handle) {
  const file = await handle.getFile();
  if (file.size > LIMIT) throw new Error('文件超过64KiB，请缩小文件');
  return textOnly(new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()));
}
export class LocalDirectory {
  constructor(handle) { localPath(handle.name); this.handle = handle; this.observed = new Map(); this.changes = new Map(); this.enabled = true; }
  async ready() {
    if (!this.enabled || await this.handle.queryPermission({ mode: 'readwrite' }) !== 'granted') throw new Error('本机目录未连接或权限已撤销，请重新授权');
  }
  disconnect() { this.enabled = false; this.observed.clear(); this.changes.clear(); }
  async directory(parts, create = false) { let handle = this.handle; for (const part of parts) handle = await handle.getDirectoryHandle(part, { create }); return handle; }
  async read(name, { observe = true } = {}) {
    await this.ready(); const parts = localPath(name), parent = await this.directory(parts.slice(0, -1));
    const text = await fileText(await parent.getFileHandle(parts.at(-1)));
    if (observe) this.observed.set(name, text); return text;
  }
  async list(name = '.') {
    await this.ready(); const entries = [], dir = await this.directory(localPath(name, true));
    for await (const [key, handle] of dir.entries()) {
      try { localPath(key); } catch { continue; }
      entries.push(`${handle.kind === 'directory' ? 'dir ' : 'file'} ${key}`);
      if (entries.length >= 1000) { entries.push('[目录列表限1000项，请指定子目录继续读取]'); break; }
    }
    return textOnly(entries.sort().join('\n'));
  }
  async files() {
    const result = []; let visited = 0; await this.ready();
    const visit = async (dir, prefix, depth) => {
      for await (const [name, handle] of dir.entries()) {
        if (++visited > 3000) throw new Error('项目目录条目过多，请选择更小的子目录');
        const relative = prefix + name; try { localPath(relative); } catch { continue; }
        if (handle.kind === 'directory') { if (depth < 10) await visit(handle, relative + '/', depth + 1); }
        else result.push({ path: relative });
        if (result.length >= 1000) return;
      }
    };
    await visit(this.handle, '', 0); return result.sort((a, b) => a.path.localeCompare(b.path));
  }
  async write(name, content, { oldText, newText, edit = false } = {}, guard = () => true) {
    await this.ready(); const parts = localPath(name);
    let parent, handle, before = null;
    try { parent = await this.directory(parts.slice(0, -1)); handle = await parent.getFileHandle(parts.at(-1)); before = await fileText(handle); }
    catch (e) { if (e.name !== 'NotFoundError') throw e; }
    if (before !== null && (!this.observed.has(name) || this.observed.get(name) !== before)) throw new Error('文件尚未读取或已被外部修改，请重新读取并确认修改');
    if (before === null && this.observed.has(name)) throw new Error('文件已被外部删除，请重新确认');
    if (edit) {
      if (before === null || typeof oldText !== 'string' || !oldText || typeof newText !== 'string') throw new Error('编辑需要已有文件及替换内容');
      const index = before.indexOf(oldText);
      if (index < 0 || before.indexOf(oldText, index + 1) >= 0) throw new Error('待替换内容必须恰好匹配一次');
      content = before.slice(0, index) + newText + before.slice(index + oldText.length);
    }
    textOnly(content);
    if (!guard()) throw new Error('任务已停止，文件未写入');
    await this.ready();
    if (!parent) parent = await this.directory(parts.slice(0, -1), true);
    // Recheck existence before creating; never overwrite a newly appeared file.
    if (!handle) {
      try { await parent.getFileHandle(parts.at(-1)); throw new Error('文件已由外部创建，请重新读取'); }
      catch (e) { if (e.name !== 'NotFoundError') throw e; }
      handle = await parent.getFileHandle(parts.at(-1), { create: true });
    }
    const writable = await handle.createWritable({ mode: 'exclusive', keepExistingData: true });
    try {
      if (before !== null && await fileText(handle) !== before) throw new Error('文件已被外部修改，请重新读取');
      await writable.write(content); await writable.truncate(new TextEncoder().encode(content).length);
      await this.ready(); if (!guard()) throw new Error('任务已停止，文件未写入');
      if (before !== null && await fileText(handle) !== before) throw new Error('写入前检测到外部修改');
      await writable.close();
    } catch (e) { await writable.abort().catch(() => {}); throw e; }
    this.observed.set(name, content);
    this.changes.set(name, { path: name, before: this.changes.has(name) ? this.changes.get(name).before : before, after: content, kind: before === null ? 'added' : 'modified' });
    return `${edit ? 'Edited' : 'Wrote'} ${name} (${new TextEncoder().encode(content).length} bytes; 本机原文件)`;
  }
  async execute(operation, args = {}, guard = () => true) {
    if (operation === 'ping') { await this.ready(); return 'Local directory connected'; }
    if (operation === 'list') return this.list(args.path);
    if (operation === 'read') return this.read(args.path);
    if (operation === 'write') return this.write(args.path, args.content, {}, guard);
    if (operation === 'edit') return this.write(args.path, null, { ...args, edit: true }, guard);
    throw new Error('本机目录不支持此工具');
  }
}
export const localSupported = () => globalThis.isSecureContext && typeof globalThis.showDirectoryPicker === 'function';
async function handleStore(owner, project, handle) {
  const db = await new Promise((resolve, reject) => { const req = indexedDB.open('yangbin-local-directories', 1); req.onupgradeneeded = () => req.result.createObjectStore('handles'); req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error); });
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction('handles', handle === undefined ? 'readonly' : 'readwrite'), store = tx.objectStore('handles');
      const key = JSON.stringify([owner, project]);
      const req = handle === undefined ? store.get(key) : handle === null ? store.delete(key) : store.put(handle, key);
      tx.oncomplete = () => resolve(req.result); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}
export async function saveLocalHandle(owner, project, handle) { return handleStore(owner, project, handle); }
export async function restoreLocalHandle(owner, project) { return handleStore(owner, project); }

import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {ToolRuntime, readPage} from '../coding-agent/src/tools.mjs';
import {LocalDirectory, localPath, mutationError} from '../yangbin-ai-agent-frontend/src/local-directory.js';
import {nativePublicTest, nativePublicTestHash} from '../coding-agent/src/native-public-tests.mjs';

// Adapt the existing conflict-aware file API to a directory explicitly selected
// in the native dialog. Every filesystem lookup is checked in the main process.
export async function nativeDirectory(workspace, {dependencyRoot, publicSourceManifest, signal} = {}) {
  workspace = await fs.realpath(workspace);
  localPath(path.basename(workspace));
  const paths = new ToolRuntime({workspace});
  const missing = error => { if(error.code === 'ENOENT') error.name = 'NotFoundError'; throw error; };
  const resolve = async (relative, create=false) => paths.resolve(relative || '.', create);
  const fileHandle = relative => ({
    kind:'file', name:path.basename(relative),
    async getFile() {
      try {
        const target=await resolve(relative), stat=await fs.stat(target);
        if(!stat.isFile())throw new Error('Not a regular file');
        return {size:stat.size, arrayBuffer:async()=>{
          if(stat.size>65536)throw new Error('文件超过64KiB');
          const bytes=await fs.readFile(await resolve(relative));
          return bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength);
        }};
      } catch(e){return missing(e);}
    },
    async createWritable() {
      const target=await resolve(relative), original=await fs.readFile(target);
      let content=Buffer.from(original), done=false;
      return {
        async write(text){if(done)throw new Error('Stream closed');content=Buffer.from(text);if(content.length>65536)throw new Error('文件超过64KiB');},
        async truncate(length){content=content.subarray(0,length);},
        async abort(){done=true;},
        async close(){
          let receipt={effect:'NO_EFFECT',phase:'VALIDATING'};
          try {
            if(done)throw new Error('Stream closed'); done=true;
            if(!original.equals(await fs.readFile(await resolve(relative))))throw new Error('文件已被外部修改');
            const temp=path.join(path.dirname(target),'.yangbin-write-'+randomUUID());
            try {
              receipt={effect:'UNKNOWN',phase:'PREPARING'};
              await fs.writeFile(temp,content,{flag:'wx',mode:0o600});
              if(!original.equals(await fs.readFile(await resolve(relative))))throw new Error('写入前检测到外部修改');
              receipt={effect:'UNKNOWN',phase:'COMMITTING'};
              await fs.rename(temp,target);
              receipt={effect:'COMMITTED',phase:'COMMITTED'};
            } finally {await fs.rm(temp,{force:true});}
          } catch(error) {throw mutationError(error,receipt);}
        }
      };
    }
  });
  const directoryHandle = relative => ({
    kind:'directory',name:relative?path.basename(relative):path.basename(workspace),
    async queryPermission(){return 'granted';},async requestPermission(){return 'granted';},
    async getDirectoryHandle(name,{create=false}={}) {
      localPath(name); const child=relative?relative+'/'+name:name;
      try { const target=await resolve(child,create);if(create)await fs.mkdir(target,{recursive:false}).catch(e=>{if(e.code!=='EEXIST')throw e;});if(!(await fs.stat(await resolve(child))).isDirectory())throw new Error('Not a directory');return directoryHandle(child); } catch(e){return missing(e);}
    },
    async getFileHandle(name,{create=false}={}) {
      localPath(name);const child=relative?relative+'/'+name:name;
      try {const target=await resolve(child,create);if(create){const h=await fs.open(target,'ax',0o600).catch(e=>{if(e.code!=='EEXIST')throw e;});await h?.close();}if(!(await fs.stat(await resolve(child))).isFile())throw new Error('Not a file');return fileHandle(child);}catch(e){return missing(e);}
    },
    async *entries(){
      const entries=await fs.readdir(await resolve(relative),{withFileTypes:true});
      for(const e of entries){
        if(e.isSymbolicLink())continue;
        const child=relative?relative+'/'+e.name:e.name;
        try{localPath(child);await resolve(child);}catch{continue;}
        yield [e.name,e.isDirectory()?directoryHandle(child):fileHandle(child)];
      }
    }
  });
  const directory = new NativeDirectory(directoryHandle(''), resolve);
  if (dependencyRoot) {
    if (process.platform !== 'linux' || !path.isAbsolute(dependencyRoot)) throw new Error('Public test verification requires a Linux dependency root');
    const manifest = publicSourceManifest ? await fs.realpath(publicSourceManifest) : null;
    if (manifest && (manifest === workspace || manifest.startsWith(workspace + path.sep))) throw new Error('Public source manifest must be outside granted workspace');
    directory.testEnvironment = {workspace,dependencyRoot:await fs.realpath(dependencyRoot),publicSourceManifest:manifest,signal};
  }
  return directory;
}

const maxFileBytes = 8 * 1024 * 1024;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
function decode(bytes) {
  const text = new TextDecoder('utf-8', {fatal:true, ignoreBOM:true}).decode(bytes);
  if (/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(text)) throw new Error('Binary file');
  return text;
}
class NativeDirectory extends LocalDirectory {
  constructor(handle, resolve) { super(handle); this.resolve = resolve; this.versions = new Map(); }
  async beginTurn() { await super.beginTurn(); this.versions.clear(); }
  disconnect() { super.disconnect(); this.versions.clear(); }
  async contents(name) {
    localPath(name);
    const target = await this.resolve(name);
    const stat = await fs.stat(target);
    if (!stat.isFile() || stat.size > maxFileBytes) throw new Error('Unsupported file type or file exceeds 8 MiB');
    const bytes = await fs.readFile(target);
    if (bytes.length > maxFileBytes) throw new Error('File exceeds 8 MiB');
    return {target, stat, bytes};
  }
  async search({query, path: root = '.', cursor = '', line = 0}, overlay = new Map()) {
    localPath(root, true);
    if (typeof query !== 'string' || !query.trim() || query.length > 512 ||
        typeof cursor !== 'string' || !Number.isSafeInteger(line) || line < 0) throw new Error('Invalid search');
    if (cursor) localPath(cursor);
    const matches = [];
    let files = 0, scanned = 0, next = null;
    const searchFile = async name => {
      const bytes = overlay.has(name) ? Buffer.from(overlay.get(name)) : (await this.contents(name)).bytes;
      scanned += bytes.length;
      const lines = decode(bytes).split('\n');
      for (let index = name === cursor ? line : 0; index < lines.length; index++) {
        const column = lines[index].indexOf(query);
        if (column < 0) continue;
        if (matches.length >= 4) { next = {cursor:name, line:index}; break; }
        matches.push({path:name, offset:index, excerpt:lines[index].slice(Math.max(0,column-30),column+110)});
      }
    };
    const visit = async prefix => {
      let entries;
      try { entries=await fs.readdir(await this.resolve(prefix), {withFileTypes:true}); }
      catch(error){if(error.code!=='ENOENT')throw error;entries=[];}
      const prefixPath=prefix==='.'?'':prefix+'/';
      const merged=new Map(entries.map(entry=>[entry.name,entry]));
      for(const name of overlay.keys()){
        if(!name.startsWith(prefixPath))continue;
        const [child,...rest]=name.slice(prefixPath.length).split('/');
        if(!rest.length || !merged.has(child))merged.set(child,{name:child,isDirectory:()=>rest.length>0,isFile:()=>!rest.length});
      }
      entries=[...merged.values()];
      const key = entry => entry.name + (entry.isDirectory() ? '/' : '');
      entries.sort((a,b) => key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0);
      for (const entry of entries) {
        if (next) break;
        const name = prefix === '.' ? entry.name : prefix + '/' + entry.name;
        try { localPath(name); } catch { continue; }
        if (entry.isDirectory()) {
          if (cursor.startsWith(name + '/') || name + '/' >= cursor) await visit(name);
          continue;
        }
        if (!entry.isFile() || name < cursor) continue;
        if (++files > 256 || scanned >= 16 * 1024 * 1024) { next = {cursor:name, line:0}; break; }
        try { await searchFile(name); }
        catch { continue; }
      }
    };
    let stat;
    try{stat=await fs.stat(await this.resolve(root || '.'));}
    catch(error){if(error.code!=='ENOENT'||(!overlay.has(root)&&![...overlay.keys()].some(name=>name.startsWith(root+'/'))))throw error;}
    if (!overlay.has(root) && (stat?.isDirectory() || !stat)) await visit(root || '.');
    else if (overlay.has(root) || stat?.isFile()) {
      if (cursor && cursor !== root) throw new Error('Search cursor must match the requested file');
      await searchFile(root);
    } else throw new Error('Search requires a regular file or directory');
    return JSON.stringify({matches,next});
  }
  async execute(operation, args = {}, guard = () => true) {
    let receipt={effect:'NO_EFFECT',phase:'VALIDATING'};
    try {
      await this.ready();
      if (operation === 'ping') return JSON.stringify({repoTools:true, publicTests:Boolean(this.testEnvironment)});
      if (this.testEnvironment && operation === 'test_snapshot') return nativePublicTestHash(this.testEnvironment);
      if (this.testEnvironment && operation === 'verify_tests') {
        localPath(args.target);
        return JSON.stringify(await nativePublicTest(this.testEnvironment,args));
      }
      if (operation === 'search') return this.search(args);
      if (operation === 'read' && args.paged) {
        const {bytes} = await this.contents(args.path);
        const text = decode(bytes);
        const page = readPage(text, args);
        this.versions.set(args.path, digest(bytes));
        if (bytes.length <= 65536) this.observed.set(args.path, new TextDecoder().decode(bytes));
        return JSON.stringify(page);
      }
      if (operation === 'edit' && args.offset !== undefined && !this.versions.has(args.path)) throw new Error('Read a current page before a ranged edit');
      if (operation === 'edit' && this.versions.has(args.path)) {
        const {bytes, target, stat} = await this.contents(args.path);
        if (digest(bytes) !== this.versions.get(args.path)) throw new Error('File changed since read');
        if (args.offset !== undefined || bytes.length > 65536 || Buffer.byteLength(args.newText || '') + bytes.length > 65536) {
          const {oldText,newText} = args;
          if (!oldText || typeof oldText !== 'string' || typeof newText !== 'string' ||
              Buffer.byteLength(oldText) + Buffer.byteLength(newText) > 65536) throw new Error('Invalid edit chunk');
          const text = decode(bytes);
          let index;
          if (args.offset !== undefined) {
            const column = args.charOffset ?? 0;
            if (!Number.isSafeInteger(args.offset) || args.offset < 0 || !Number.isSafeInteger(column) || column < 0) throw new Error('Invalid edit offset');
            index = 0;
            for (let line = 0; line < args.offset; line++) {
              const end = text.indexOf('\n',index);
              if (end < 0) throw new Error('Edit offset outside file');
              index = end + 1;
            }
            const end = text.indexOf('\n',index);
            if (column > (end < 0 ? text.length : end) - index || !text.startsWith(oldText,index+column)) throw new Error('Expected text differs at edit offset');
            index += column;
          } else {
            index = text.indexOf(oldText);
            if (index < 0 || text.indexOf(oldText,index+1) >= 0) throw new Error('Expected exactly one occurrence');
          }
          const updated = Buffer.from(text.slice(0,index) + newText + text.slice(index+oldText.length));
          if (updated.length > maxFileBytes) throw new Error('File exceeds 8 MiB');
          decode(updated);
          const temporary = path.join(path.dirname(target), '.yangbin-write-' + randomUUID());
          try {
            receipt={effect:'UNKNOWN',phase:'PREPARING'};
            await fs.writeFile(temporary, updated, {flag:'wx',mode:0o600});
            await fs.chmod(temporary, stat.mode & 0o777);
            await this.ready();
            if (!guard() || !bytes.equals((await this.contents(args.path)).bytes)) throw new Error('Edit cancelled or file changed');
            receipt={effect:'UNKNOWN',phase:'COMMITTING'};
            await fs.rename(temporary, target);
            receipt={effect:'COMMITTED',phase:'COMMITTED'};
          } finally { await fs.rm(temporary, {force:true}); }
          this.versions.set(args.path,digest(updated));
          if (updated.length <= 65536) this.observed.set(args.path,new TextDecoder().decode(updated));
          if (bytes.length <= 65536 && updated.length <= 65536) {
            const prior = this.changes.get(args.path);
            this.changes.set(args.path,{path:args.path,kind:prior?.kind ?? 'modified',before:prior ? prior.before : text,after:decode(updated)});
          } else this.changes.set(args.path,{path:args.path,kind:'modified',partial:true});
          return 'Edited ' + args.path;
        }
      }
      const result = await super.execute(operation,args,guard);
      if (['write','edit'].includes(operation)) {
        receipt={effect:'COMMITTED',phase:'COMMITTED'};
        this.versions.delete(args.path);
      }
      return result;
    } catch(error) {
      if(['write','edit'].includes(operation))throw mutationError(error,receipt);
      throw error;
    }
  }
}

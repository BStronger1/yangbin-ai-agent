import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {localPath, mutationError} from '../yangbin-ai-agent-frontend/src/local-directory.js';
import {readPage} from '../coding-agent/src/tools.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
const instanceId = randomUUID();
const activeWrites = new Map();
const limit = 256 * 1024;
const journalId = value => typeof value === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(value);
const signature = value => Array.isArray(value) ? value.map(signature) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key,signature(value[key])])) : value;
export const parametersHash = (operation,args) => hash(JSON.stringify(signature({operation,args})));
const readFile = async file => {try{return await fs.readFile(file,'utf8');}catch(error){if(error.code==='ENOENT')return null;throw error;}};
async function record(file,data,exclusive=false) {
  const temp = path.join(path.dirname(file),'.pending-'+randomUUID());
  try {
    const handle = await fs.open(temp,'wx',0o600);
    try {await handle.writeFile(JSON.stringify(data)); await handle.sync();} finally {await handle.close();}
    if(exclusive) await fs.link(temp,file);
    else await fs.rename(temp,file);
  } finally { await fs.rm(temp,{force:true}); }
}
async function json(file) {const raw=await readFile(file);return raw===null?null:JSON.parse(raw);}
function processExists(pid) {
  try {process.kill(pid,0);return true;} catch(error) {return error.code!=='ESRCH';}
}
function text(bytes) {
  const value=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);
  if(/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(value)) throw new Error('候选仅支持 UTF-8 文本');
  return value;
}
function edit(before,args) {
  const {oldText,newText}=args;
  if(before===null || typeof oldText!=='string' || !oldText || typeof newText!=='string')throw new Error('编辑需要已有文件和替换内容');
  let offset=before.indexOf(oldText);
  if(args.offset===undefined) {
    if(offset<0 || before.indexOf(oldText,offset+1)>=0)throw new Error('待替换内容必须恰好匹配一次');
  } else {
    const column=args.charOffset??0;
    if(!Number.isSafeInteger(args.offset)||args.offset<0||!Number.isSafeInteger(column)||column<0)throw new Error('无效编辑位置');
    offset=0;
    for(let n=0;n<args.offset;n++){const end=before.indexOf('\n',offset);if(end<0)throw new Error('编辑超出文件');offset=end+1;}
    const eol=before.indexOf('\n',offset);
    if(column>(eol<0?before.length:eol)-offset||!before.startsWith(oldText,offset+column))throw new Error('指定位置内容已经变化');
    offset+=column;
  }
  return before.slice(0,offset)+newText+before.slice(offset+oldText.length);
}

// The store is outside the granted repository. Only these private candidate files
// are ever replaced. No hash-then-rename operation can overwrite an IDE edit.
export class CandidateWorkspace {
  constructor({state,identity,directory,fault=async()=>{}}) {
    if(!identity.every(value=>typeof value==='string' && value.length>0))throw new Error('缺少工作区身份');
    this.root=path.join(state,hash(JSON.stringify(identity)));this.directory=directory;this.fault=fault;
    this.observed=new Map();
  }
  file(name){localPath(name);return path.join(this.root,'file-'+hash(name)+'.json');}
  op(id){if(!journalId(id))throw new Error('无效操作编号');return path.join(this.root,'op-'+id+'.json');}
  async original(name){
    localPath(name);
    try {return (await this.directory.contents(name)).bytes;}catch(error){if(error.code==='ENOENT')return null;throw error;}
  }
  async current(name){const bytes=await this.original(name);return bytes===null?null:hash(bytes);}
  async staged(){
    let names;try{names=await fs.readdir(this.root);}catch(e){if(e.code==='ENOENT')return [];throw e;}
    return Promise.all(names.filter(n=>/^file-[a-f0-9]{64}\.json$/.test(n)).map(n=>json(path.join(this.root,n))));
  }
  async read(args){
    const staged=await json(this.file(args.path));
    if(staged)return args.paged?JSON.stringify(readPage(staged.after,args)):staged.after;
    const bytes=await this.original(args.path);if(bytes===null)throw new Error('文件不存在');
    const value=text(bytes);
    if(bytes.length<=limit)this.observed.set(args.path,{value,hash:hash(bytes)});
    return args.paged?JSON.stringify(readPage(value,args)):value;
  }
  async list(name='.'){
    const parts=localPath(name,true),prefix=parts.length?parts.join('/')+'/':'';
    const staged=(await this.staged()).filter(c=>c.path.startsWith(prefix));
    let result;
    try {result=await this.directory.list(name);} catch(e){if(!staged.length||!(e.code==='ENOENT'||e.name==='NotFoundError'))throw e;result='';}
    const entries=new Set(result.split('\n').filter(Boolean));
    for(const candidate of staged){const [child,...rest]=candidate.path.slice(prefix.length).split('/');entries.add((rest.length?'dir ':'file')+' '+child);}
    return [...entries].sort().join('\n');
  }
  async writer(acquire) {
    let names;try {names=await fs.readdir(this.root);} catch(e){if(e.code==='ENOENT'&&!acquire)return false;throw e;}
    const last=names.filter(n=>/^writer-\d{9}\.json$/.test(n)).sort().at(-1);
    const owner=last?await json(path.join(this.root,last)):null;
    if(owner?.instance===instanceId)return false;
    if(owner && processExists(owner.pid))return true;
    if(!acquire)return false;
    const number=last?Number(last.slice(7,-5))+1:1;
    try {await record(path.join(this.root,'writer-'+String(number).padStart(9,'0')+'.json'),{instance:instanceId,pid:process.pid},true);}
    catch(e){if(e.code==='EEXIST')return this.writer(acquire);throw e;}
    return false;
  }
  async reconcile(operationId,expected) {
    const entry=await json(this.op(operationId));
    if(!entry)return {operationId,phase:'MISSING',effect:'CANDIDATE'};
    if(expected && expected!==entry.parametersHash)throw new Error('同一操作编号携带不同参数');
    const current=await json(this.file(entry.candidate.path));
    let baselineMatches=null;
    try {baselineMatches=(await this.current(entry.candidate.path))===entry.candidate.baseHash;} catch { /* unreadable/renamed paths are unknown */ }
    return {...entry,bytesMatch:current?.afterHash===entry.candidate.afterHash && hash(current.after)===current.afterHash,
      writerAlive:activeWrites.has(this.root)||await this.writer(false),baselineMatches,candidate:{...entry.candidate,conflict:baselineMatches!==true}};
  }
  async propose(operation,args,operationId,authorize) {
    let receipt={effect:'NO_EFFECT',phase:'VALIDATING'};
    try {
      const opFile=this.op(operationId),digest=parametersHash(operation,args);
      if(!['write','edit'].includes(operation))throw new Error('仅支持文件候选');
      localPath(args.path);
      // A reused identifier may already own an uncertain operation. A failure
      // while reconciling it cannot be interpreted as permission to replay it.
      receipt={effect:'UNKNOWN',phase:'PREPARING'};
      const prior=await this.reconcile(operationId,digest);
      if(prior.phase!=='MISSING')return prior; // Reading a historic receipt never resumes an uncertain mutation.
      if(activeWrites.has(this.root))throw new Error('旧执行器仍可能写入，仅允许核对记录');
      receipt={effect:'NO_EFFECT',phase:'VALIDATING'};
      activeWrites.set(this.root,operationId);
      try {await this.prepare(operation,args,operationId,opFile,digest,authorize);receipt={effect:'COMMITTED',phase:'COMMITTED'};}
      finally {activeWrites.delete(this.root);}
      return await this.reconcile(operationId,digest);
    } catch(error) {throw mutationError(error,receipt);}
  }
  async prepare(operation,args,operationId,opFile,digest,authorize) {
    let receipt={effect:'NO_EFFECT',phase:'VALIDATING'};
    try {
      await authorize();
      receipt={effect:'UNKNOWN',phase:'PREPARING'};
      if(await this.writer(false))throw mutationError(new Error('旧执行器仍可能写入，仅允许核对记录'),{effect:'UNKNOWN',phase:'PREPARING'});
      let names;try{names=await fs.readdir(this.root);}catch(error){if(error.code!=='ENOENT')throw error;names=[];}
      for(const name of names.filter(n=>n.startsWith('op-'))){
        if((await json(path.join(this.root,name))).phase==='PREPARED')throw mutationError(new Error('此前操作结果待核对，不能继续写入'),{effect:'UNKNOWN',phase:'PREPARING'});
      }
      receipt={effect:'NO_EFFECT',phase:'VALIDATING'};
      const existing=await json(this.file(args.path));
      const onDisk=await this.original(args.path),seen=this.observed.get(args.path);
      if(!existing && !seen && onDisk!==null)throw new Error('请先读取需要修改的文件');
      const before=existing?existing.before:(seen?.value??null);
      const baseHash=existing?existing.baseHash:(seen?.hash??null);
      const after=operation==='edit'?edit(existing?existing.after:before,args):args.content;
      if(typeof after!=='string'||Buffer.byteLength(before??'')+Buffer.byteLength(after)>limit)throw new Error('可交接的单文件候选和基线合计不得超过 256 KiB');
      text(Buffer.from(after));
      const candidate={path:args.path,before,after,baseHash,afterHash:hash(after)};
      const entry={operationId,parametersHash:digest,phase:'PREPARED',effect:'CANDIDATE',candidate};
      await authorize();
      // Pure predicate errors above create neither private files nor receipts.
      // From the first filesystem mutation onward, interrupted work needs review.
      receipt={effect:'UNKNOWN',phase:'PREPARING'};
      await fs.mkdir(this.root,{recursive:true,mode:0o700});
      if(await this.writer(true))throw new Error('旧执行器仍可能写入，仅允许核对记录');
      // Another process may have acquired/released ownership during validation.
      for(const name of (await fs.readdir(this.root)).filter(n=>n.startsWith('op-'))){
        if((await json(path.join(this.root,name))).phase==='PREPARED')throw new Error('此前操作结果待核对，不能继续写入');
      }
      if(JSON.stringify(await json(this.file(args.path)))!==JSON.stringify(existing))throw new Error('候选在校验期间发生变化，请重新读取');
      await record(opFile,entry,true);
      await this.fault('prepared');
      await authorize();
      receipt={effect:'UNKNOWN',phase:'COMMITTING'};
      await record(this.file(args.path),candidate);
      receipt={effect:'COMMITTED',phase:'COMMITTED'};
      await this.fault('written');
      await record(opFile,{...entry,phase:'CONFIRMED'});
      await this.fault('confirmed');
    } catch(error) {throw mutationError(error,receipt);}
  }
}

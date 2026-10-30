import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
export const sha256 = value => createHash('sha256').update(value).digest('hex');
export async function fileHash(file) {
  try { const stat=await fs.lstat(file); if(!stat.isFile()||stat.isSymbolicLink()||stat.nlink>1||stat.size>262144)throw Error('Unsafe file state');return sha256(await fs.readFile(file)); }
  catch(error){if(error.code==='ENOENT')return null;throw error;}
}
async function syncDirectory(dir) {
  // Windows does not expose fsync on directory handles. Process-crash guarantee only.
  if(process.platform==='win32')return;
  const handle=await fs.open(dir,'r');try{await handle.sync()}finally{await handle.close()}
}
async function durableJson(file,value) {
  const temp=file+'.'+randomUUID()+'.tmp';const handle=await fs.open(temp,'wx',0o600);
  try{await handle.writeFile(JSON.stringify(value));await handle.sync()}finally{await handle.close()}
  await fs.rename(temp,file);await syncDirectory(path.dirname(file));
}
// Journal outside workspace; never exposed as model-readable project content.
export class FileOperations {
  constructor({directory,identity,checkpoint=async()=>{}}){this.directory=path.resolve(directory);this.identity=identity;this.checkpoint=checkpoint;}
  async mutate({name,args,callId,location,content,resolve,signal}) {
    if(typeof callId!=='string'||!callId||callId.length>500)throw Error('Invalid operation identifier');
    await fs.mkdir(this.directory,{recursive:true,mode:0o700});
    if((await fs.lstat(this.directory)).isSymbolicLink())throw Error('Invalid journal directory');
    const lock=path.join(this.directory,'writer.lock');
    const owner=await fs.open(lock,'wx',0o600); // A crashed lock is never silently stolen.
    try{await owner.writeFile(JSON.stringify({pid:process.pid}));await owner.sync()}finally{await owner.close()}
    let safeToUnlock=false;
    try {
      const operationId=sha256(callId),file=path.join(this.directory,operationId+'.json');
      const argumentsHash=sha256(JSON.stringify([name,args.path,args.content??null,args.oldText??null,args.newText??null]));
      let previous;try{previous=JSON.parse(await fs.readFile(file,'utf8'))}catch(e){if(e.code!=='ENOENT')throw e}
      if(previous){
        if(previous.argumentsHash!==argumentsHash)throw Error('Operation ID reused with different arguments');
        const current=await fileHash(location);
        if(previous.state==='APPLIED'&&current===previous.afterHash){safeToUnlock=true;return previous.result;}
        throw Error('Existing operation requires recovery review; mutation will not be replayed');
      }
      const beforeHash=await fileHash(location);content=await content();const afterHash=sha256(Buffer.from(content));
      const record={version:1,operationId,callId,identity:this.identity,path:args.path,tool:name,argumentsHash,beforeHash,afterHash,state:'PREPARED',createdAt:new Date().toISOString(),result:`${name==='edit'?'Edited':'Wrote'} ${args.path} (${Buffer.byteLength(content)} bytes)`};
      await durableJson(file,record);await this.checkpoint('prepared');
      await atomicReplace({location,content,beforeHash,resolve,signal,checkpoint:this.checkpoint});
      record.state='APPLIED';await durableJson(file,record);await this.checkpoint('committed');
      safeToUnlock=true;return record.result;
    } catch(error){error.recoveryRequired=true;throw error;} finally {
      // Preserve uncertain records and their lock. Java permits a NEW turn only after death + review.
      if(safeToUnlock)await fs.unlink(lock);
    }
  }
}
export async function atomicReplace({location,content,beforeHash,resolve,signal,checkpoint=async()=>{}}){
  await fs.mkdir(path.dirname(location),{recursive:true});await resolve();signal?.throwIfAborted();
  const temp=path.join(path.dirname(location),'.coding-write-'+randomUUID()+'.tmp');
  let handle;
  try{
    let mode=0o600;try{mode=(await fs.lstat(location)).mode&0o777}catch(e){if(e.code!=='ENOENT')throw e}
    handle=await fs.open(temp,'wx',mode);await handle.writeFile(content,'utf8');await handle.chmod(mode);await handle.sync();await handle.close();handle=null;
    await checkpoint('staged');await resolve();signal?.throwIfAborted();
    if(await fileHash(location)!==beforeHash)throw Error('File changed since operation preparation; review required');
    await fs.rename(temp,location);await syncDirectory(path.dirname(location));await checkpoint('replaced');
  }finally{await handle?.close();await fs.unlink(temp).catch(e=>{if(e.code!=='ENOENT')throw e})}
}

import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {ToolRuntime} from '../coding-agent/src/tools.mjs';
import {LocalDirectory, localPath} from '../yangbin-ai-agent-frontend/src/local-directory.js';

// Adapt the existing conflict-aware file API to a directory explicitly selected
// in the native dialog. Every filesystem lookup is checked in the main process.
export async function nativeDirectory(workspace) {
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
          if(done)throw new Error('Stream closed'); done=true;
          if(!original.equals(await fs.readFile(await resolve(relative))))throw new Error('文件已被外部修改');
          const temp=path.join(path.dirname(target),'.yangbin-write-'+randomUUID());
          try {
            await fs.writeFile(temp,content,{flag:'wx',mode:0o600});
            if(!original.equals(await fs.readFile(await resolve(relative))))throw new Error('写入前检测到外部修改');
            await fs.rename(temp,target);
          } finally {await fs.rm(temp,{force:true});}
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
  return new LocalDirectory(directoryHandle(''));
}

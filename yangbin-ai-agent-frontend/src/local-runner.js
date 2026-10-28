const operations = ['start', 'preview', 'process_status', 'stop'];
export class LocalRunner {
  constructor(url, token) {
    const endpoint = new URL(url);
    if (endpoint.protocol !== 'http:' || endpoint.hostname !== '127.0.0.1' || !endpoint.port || endpoint.pathname !== '/' || endpoint.search || endpoint.hash || endpoint.username || endpoint.password) throw new Error('运行器地址必须是本机 127.0.0.1 地址');
    if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('请粘贴运行器显示的连接码');
    this.url=endpoint.origin; this.token=token; this.binding=null;
  }
  async rpc(operation, args, requestId=crypto.randomUUID()) {
    const response=await fetch(this.url+'/rpc',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+this.token},body:JSON.stringify({operation,args,requestId,binding:this.binding}),signal:AbortSignal.timeout(15000),credentials:'omit',redirect:'error'});
    if(!response.ok)throw new Error('本机运行器拒绝连接，请检查地址、连接码及浏览器本地网络授权');
    const value=await response.json(); if(value.error)throw new Error(value.error); return value;
  }
  async pair(directory) {
    await directory.ready(); const challenge=crypto.randomUUID(), proof=Array.from(crypto.getRandomValues(new Uint8Array(32)),x=>x.toString(16).padStart(2,'0')).join('');
    const name='.yangbin-pair-'+challenge; let created=false;
    try {
      // A random file proves the browser handle and helper refer to the SAME directory.
      const handle=await directory.handle.getFileHandle(name,{create:true});created=true;
      const stream=await handle.createWritable();await stream.write(proof);await stream.close();
      const value=await this.rpc('pair',{challenge,proof});this.binding=value.binding;this.workspace=value.workspace;this.platform=value.platform;return this;
    }finally{if(created)await directory.handle.removeEntry(name);}
  }
  async execute(operation,args,requestId) {
    if(!operations.includes(operation)||!this.binding)throw new Error('本机运行器未连接');
    return JSON.stringify((await this.rpc(operation,args,requestId)).output);
  }
  disconnect(){this.binding=null;this.token='';}
}

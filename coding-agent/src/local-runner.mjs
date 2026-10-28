import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { ToolRuntime } from './tools.mjs';

// Explicitly launched, local-user process execution. This is NOT an OS sandbox.
// No model credentials are accepted by this helper. Browser requests require both
// an exact configured origin and an unguessable, memory-only pairing token.
export class LocalProcessManager {
  constructor(workspace) { this.workspace = path.resolve(workspace); this.jobs = new Map(); this.paths = new ToolRuntime({ workspace }); }
  async init() { this.workspace = await fs.realpath(this.workspace); this.paths.workspace = this.workspace; return this; }
  status(id) {
    const job = this.jobs.get(id); if (!job) throw new Error('Unknown process');
    return { id, state: job.state, exitCode: job.exitCode ?? null, url: job.url, output: job.output, command: job.command };
  }
  async start({ command }) {
    if (typeof command !== 'string' || !command.trim() || command.length > 8192 || command.includes('\0')) throw new Error('Invalid command');
    if ([...this.jobs.values()].filter(j => j.state === 'RUNNING').length >= 4 || this.jobs.size >= 100) throw new Error('Local process limit reached');
    const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => /^(path|systemroot|windir|temp|tmp|lang|pathext)$/i.test(k)));
    const windows = process.platform === 'win32';
    const child = spawn(windows ? 'powershell.exe' : '/bin/sh', windows ? ['-NoLogo','-NoProfile','-NonInteractive','-Command',command] : ['-c',command], { cwd: this.workspace, env, windowsHide: true, detached: !windows, stdio: ['ignore','pipe','pipe'] });
    const id = randomUUID(), job = { child, state:'RUNNING', output:'', command }; this.jobs.set(id, job);
    const append = chunk => { job.output = (job.output + chunk.toString('utf8')).slice(-12000); };
    child.stdout.on('data', append); child.stderr.on('data', append);
    child.on('error', () => { job.state='FAILED'; job.output += '\nUnable to start local command'; });
    child.on('close', code => { if(job.state==='RUNNING')job.state=code===0?'EXITED':'FAILED'; job.exitCode=code; });
    await new Promise(resolve => setTimeout(resolve, 200)); return this.status(id);
  }
  async stop(id) {
    const job = this.jobs.get(id); if(!job)throw new Error('Unknown process');
    if (job.server) { job.server.closeAllConnections(); await new Promise(resolve => job.server.close(resolve)); job.server=null; }
    else if (job.child && job.state === 'RUNNING') {
      if(process.platform==='win32') await new Promise(resolve => { const kill=spawn('taskkill.exe',['/PID',String(job.child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'}); kill.on('error',resolve);kill.on('close',resolve); });
      else { try { process.kill(-job.child.pid,'SIGKILL'); } catch(error) { if(error.code!=='ESRCH')throw error; } }
    }
    job.state='STOPPED'; return this.status(id);
  }
  async preview({ path: entry = 'index.html' } = {}) {
    const file = await this.paths.resolve(entry); if (!(await fs.stat(file)).isFile()) throw new Error('Preview entry must be a file');
    if ([...this.jobs.values()].filter(j=>j.state==='RUNNING').length>=4 || this.jobs.size>=100)throw new Error('Local process limit reached');
    const types={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.mjs':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.svg':'image/svg+xml','.png':'image/png','.jpg':'image/jpeg','.webp':'image/webp','.ico':'image/x-icon','.mp3':'audio/mpeg','.wav':'audio/wav','.woff2':'font/woff2'};
    const server=http.createServer(async(req,res)=>{
      try {
        if(!['GET','HEAD'].includes(req.method)||req.headers.origin && req.headers.origin!==`http://127.0.0.1:${server.address().port}`)throw new Error('Forbidden');
        const relative=decodeURIComponent(new URL(req.url,'http://localhost').pathname).slice(1) || entry;
        const target=await this.paths.resolve(relative); const mime=types[path.extname(target).toLowerCase()];
        if(!mime)throw new Error('Unsupported preview file'); const stat=await fs.stat(target);
        if(!stat.isFile()||stat.size>16*1024*1024)throw new Error('Preview file too large');
        res.writeHead(200,{'Content-Type':mime,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'});
        res.end(req.method==='HEAD'?undefined:await fs.readFile(target));
      }catch{res.writeHead(404);res.end('Not found');}
    });
    await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
    const id=randomUUID(); this.jobs.set(id,{server,state:'RUNNING',output:'Static project preview is serving on this computer.',url:`http://127.0.0.1:${server.address().port}/${entry.split('/').map(encodeURIComponent).join('/')}`});
    return this.status(id);
  }
  async execute(operation,args={}) {
    if(operation==='start')return this.start(args);
    if(operation==='preview')return this.preview(args);
    if(operation==='process_status')return args.id?this.status(args.id):[...this.jobs.keys()].map(id=>this.status(id));
    if(operation==='stop')return this.stop(args.id);
    throw new Error('Unsupported local process operation');
  }
  async close() { for(const id of this.jobs.keys())await this.stop(id); }
}

export async function createLocalRunner({ workspace, origin, token = randomBytes(32).toString('hex'), port = 0 }) {
  const allowed = new URL(origin); if(allowed.origin !== origin || !['https:','http:'].includes(allowed.protocol))throw new Error('Exact application origin required');
  if(typeof token!=='string'||token.length<32)throw new Error('Pairing token too short');
  const manager=await new LocalProcessManager(workspace).init();
  const receipts=new Map(), bindings=new Set();
  const secret=Buffer.from(token);
  const server=http.createServer(async(req,res)=>{
    const send=(code,value)=>{res.writeHead(code,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(value));};
    try {
      if(req.headers.host!==`127.0.0.1:${server.address().port}` || req.headers.origin!==origin)return send(403,{error:'Origin or host rejected'});
      res.setHeader('Access-Control-Allow-Origin',origin);res.setHeader('Vary','Origin');
      if(req.method==='OPTIONS'){res.setHeader('Access-Control-Allow-Methods','POST');res.setHeader('Access-Control-Allow-Headers','authorization,content-type');res.setHeader('Access-Control-Allow-Private-Network','true');return send(200,{});}
      const supplied=Buffer.from((req.headers.authorization||'').replace(/^Bearer /,''));
      if(supplied.length!==secret.length||!timingSafeEqual(secret,supplied))return send(401,{error:'Pairing required'});
      if(req.method!=='POST'||req.url!=='/rpc'||!req.headers['content-type']?.startsWith('application/json'))return send(400,{error:'Invalid request'});
      let body=''; for await(const chunk of req){body+=chunk;if(Buffer.byteLength(body)>16384)throw new Error('Request too large');}
      const {operation,args={},requestId,binding}=JSON.parse(body);
      if(operation==='pair'){
        if(!/^[a-f0-9-]{36}$/.test(args.challenge)||typeof args.proof!=='string'||args.proof.length!==64)throw new Error('Invalid pairing challenge');
        const file=await manager.paths.resolve('.yangbin-pair-'+args.challenge);
        if((await fs.readFile(file,'utf8'))!==args.proof)throw new Error('Selected directory does not match local runner');
        const id=randomUUID();bindings.add(id);return send(200,{binding:id,workspace:manager.workspace,platform:process.platform,capabilities:['start','preview','process_status','stop']});
      }
      if(!bindings.has(binding))return send(403,{error:'Project pairing required'});
      if(!/^[a-f0-9-]{36}$/.test(requestId))throw new Error('Request ID required');
      if(receipts.has(requestId))return send(200,await receipts.get(requestId));
      if(receipts.size>=1000)throw new Error('Runner request limit reached; reconnect after restart');
      const result=manager.execute(operation,args).then(output=>({output}),()=>({error:'Local operation failed; inspect process status before retrying'}));
      receipts.set(requestId,result);return send(200,await result);
    }catch{if(!res.headersSent)send(400,{error:'Local operation rejected'});else res.end();}
  });
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});
  return { manager, token, url:`http://127.0.0.1:${server.address().port}`, close:async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));await manager.close();} };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [workspace,origin='https://www.yangbinai.homes']=process.argv.slice(2);
  if(!workspace)throw new Error('Usage: node src/local-runner.mjs <project-directory> [application-origin]');
  const runner=await createLocalRunner({workspace,origin});
  // Pairing material is shown only to the local user, never sent to the product server.
  process.stdout.write(JSON.stringify({url:runner.url,token:runner.token,workspace:runner.manager.workspace})+'\n');
  for(const signal of ['SIGINT','SIGTERM'])process.once(signal,async()=>{await runner.close();process.exit(0);});
}

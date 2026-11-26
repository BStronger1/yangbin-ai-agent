// Invoked by CodingLocalFilesEndToEndTest. Java and pi use a loopback scripted provider.
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createRequire} from 'node:module';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {setTimeout as delay} from 'node:timers/promises';
const [javaRoot,output]=process.argv.slice(2);
const root=fileURLToPath(new URL('../../../',import.meta.url));
const frontend=path.join(root,'yangbin-ai-agent-frontend');
const {_electron,chromium}=await import(pathToFileURL(path.join(frontend,'node_modules/playwright/index.mjs')));
const java=new URL(javaRoot);java.hostname='127.0.0.1';
const recorded=[],connections=new Set();
let disconnectedReceiptPath='';
const server=http.createServer(async(req,res)=>{
  try{
    const url=new URL(req.url,'http://localhost');
    const json=body=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(body));};
    if(url.pathname==='/api/auth/me')return json({user:{id:'admin',username:'fixture',role:'admin',csrf:'fixture-csrf'}});
    if(url.pathname.startsWith('/api/')){
      const chunks=[];for await(const piece of req)chunks.push(piece);const body=Buffer.concat(chunks);
      if(disconnectedReceiptPath && req.method==='POST' && (url.pathname===disconnectedReceiptPath+'/local-receipts' ||
        (url.pathname===disconnectedReceiptPath+'/local-files/result' && JSON.parse(body).receipt))){
        res.writeHead(503,{'Content-Type':'application/json'});res.end('{"message":"fixture: receipt transport disconnected"}');return;
      }
      const headers={'Content-Type':'application/json','X-Internal-Token':'coding-fixture-internal','X-User-Id':'admin','X-User-Role':'admin'};
      if(req.headers['x-local-client'])headers['X-Local-Client']=req.headers['x-local-client'];
      const response=await fetch(new URL(req.url,java),{method:req.method,headers,...(body.length?{body}:{})});
      const payload=await response.text();
      if(response.status>=400 || !['GET','HEAD'].includes(req.method))recorded.push([req.method,url.pathname,response.status,payload.slice(0,150)]);
      res.writeHead(response.status,{'Content-Type':response.headers.get('content-type')||'application/json'});res.end(payload);return;
    }
    const asset=url.pathname.startsWith('/assets/')?path.join(frontend,'dist/assets',path.basename(url.pathname)):path.join(frontend,'dist/index.html');
    res.writeHead(200,{'Content-Type':({'.html':'text/html','.css':'text/css','.js':'application/javascript'})[path.extname(asset)]||'application/octet-stream'});
    res.end(await fs.readFile(asset));
  }catch(error){if(!res.headersSent)res.writeHead(500);res.end(String(error.message));}
});
server.on('connection',socket=>{connections.add(socket);socket.on('close',()=>connections.delete(socket));});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin='http://127.0.0.1:'+server.address().port,base=origin+'/api/ai/coding';
async function call(method,route,input){
  const res=await fetch(base+route,{method,headers:{'Content-Type':'application/json'},...(input?{body:JSON.stringify(input)}:{})});
  const result=await res.text();assert.ok(res.ok,method+' '+route+' '+res.status+' '+result);return JSON.parse(result);
}
async function until(fn,message,ms=30000){const deadline=Date.now()+ms;do{const v=await fn();if(v)return v;await delay(140);}while(Date.now()<deadline);throw new Error(message);}
const desktopRequire=createRequire(path.join(root,'desktop/package.json'));
const executable=desktopRequire('electron');
await fs.access(executable);
await fs.access(path.join(frontend,'dist/index.html'));
let app,page,mobile;
const report=[];
try{
  for(const stage of ['prepared','written','confirmed']){
    const run=path.join(output,stage),repo=path.join(run,'shared'),profile=path.join(run,'profile'),marker=path.join(run,'crash-point'),writes=path.join(run,'candidate-writes.jsonl');
    await fs.mkdir(repo,{recursive:true});await fs.mkdir(profile,{recursive:true});
    await fs.writeFile(writes,'');
    const project=await call('POST','/projects',{name:'A3-'+stage,source:'local'});
    const session=await call('POST','/sessions',{projectId:project.id,approvalMode:'on-risk',title:'A3-'+stage});
    disconnectedReceiptPath='/api/ai/coding/sessions/'+session.id;
    await fs.writeFile(path.join(profile,'project-directories.json'),JSON.stringify({[JSON.stringify([origin,'admin',project.id])]:repo}));
    const launch=async(crashAt)=>{
      app=await _electron.launch({executablePath:executable,args:[path.join(root,'desktop/main.mjs')],env:{...process.env,YANGBIN_DEV_ORIGIN:origin,YANGBIN_DEV_DATA:profile,YANGBIN_DEV_HIDDEN:'1'}});
      page=await app.firstWindow();page.setDefaultTimeout(100000);page.on('pageerror',error=>console.log('PAGE_ERROR',String(error)));
      await page.waitForURL(url=>url.origin===origin,{waitUntil:'domcontentloaded'});
      await page.getByTestId('coding-prompt').waitFor();
      // Both incarnations count actual private file replacements. Only the first
      // receives a barrier, after the durable operation has returned.
      await app.evaluate(async(_,{crashAt,marker,writes})=>{
      const fs=process.getBuiltinModule('fs/promises');
      for(const key of ['link','rename']){
        const actual=fs[key].bind(fs);
        fs[key]=async function(src,dst,...args){
          const value=await actual(src,dst,...args),name=String(dst).split(/[\\/]/).at(-1);
          if(!String(dst).includes('private-candidates-v1'))return value;
          const phase=key==='link'&&name.startsWith('op-')?'prepared':key==='rename'&&name.startsWith('file-')?'written':key==='rename'&&name.startsWith('op-')?'confirmed':'';
          if(phase==='written')await fs.appendFile(writes,JSON.stringify({pid:process.pid,file:name})+'\n');
          if(crashAt && phase===crashAt){await fs.writeFile(marker,phase);await new Promise(()=>{});}
          return value;
        };
      }
      },{crashAt,marker,writes});
      await page.goto(origin+'/coding?session='+session.id,{waitUntil:'domcontentloaded'});
    };
    await launch(stage);
    await page.getByTestId('approval-policy').click();
    await page.getByTestId('execution-mode').selectOption('react');
    await page.getByRole('button',{name:'关闭审批策略'}).click();
    await page.getByTestId('coding-prompt').fill('create hello.txt');
    await page.getByTestId('coding-send').click();
    await page.getByTestId('approval-allow').click();
    await until(()=>fs.readFile(marker,'utf8').catch(()=>false),'did not reach '+stage);
    const processHandle=app.process(),writerPid=await app.evaluate(()=>process.pid),exited=once(processHandle,'exit');
    if(process.platform==='win32')await promisify(execFile)('taskkill.exe',['/PID',String(writerPid),'/T','/F'],{windowsHide:true});
    else processHandle.kill('SIGKILL');
    await exited;app=null;page=null;
    await until(()=>{try{process.kill(writerPid,0);return false;}catch(error){if(error.code==='ESRCH')return true;throw error;}},'old executor still alive',10000);
    await call('POST','/sessions/'+session.id+'/cancel',{});
    let list=await call('GET','/sessions/'+session.id+'/local-receipts');
    assert.equal(list.length,1);assert.equal(list[0].phase,'CLAIMED');
    disconnectedReceiptPath='';
    await launch();
    const expected=stage==='confirmed'?'CONFIRMED':'PREPARED';
    const row=await until(async()=>{const [row]=await call('GET','/sessions/'+session.id+'/local-receipts');return row?.phase===expected&&row;},'receipt did not reconcile '+stage);
    assert.equal(row.bytesMatch,stage!=='prepared');assert.equal(row.writerAlive,false);
    assert.equal((await call('GET','/sessions/'+session.id)).status,'CANCELLED');
    assert.deepEqual(await fs.readdir(repo),[]);
    const files=await fs.readdir(path.join(profile,'private-candidates-v1'),{recursive:true});
    assert.equal(files.filter(file=>path.basename(file).startsWith('file-')).length,stage==='prepared'?0:1);
    if(stage==='confirmed'){
      const c=await call('GET','/sessions/'+session.id+'/local-receipts/'+row.operationId+'/candidate');
      assert.equal(c.after,'hello coding\n');assert.equal(c.before,null);
      const bundled=await fs.access(chromium.executablePath()).then(()=>true,()=>false);
      mobile=await chromium.launch({...(bundled||process.platform!=='win32'?{}:{channel:'msedge'}),headless:true});
      const phone=await mobile.newPage({viewport:{width:390,height:844}});
      await phone.goto(origin+'/coding?session='+session.id);
      await phone.getByRole('button',{name:'查看候选'}).click();
      await phone.getByText('hello coding',{exact:true}).waitFor();
      const [download]=await Promise.all([phone.waitForEvent('download'),phone.getByRole('button',{name:'下载补丁'}).click()]);
      const exported=path.join(run,'downloaded.patch'),review=path.join(run,'review-copy');
      await download.saveAs(exported);await fs.mkdir(review);
      await promisify(execFile)('git',['init','--quiet'],{cwd:review,windowsHide:true});
      for(const flags of [['--check'],[]])await promisify(execFile)('git',['-c','core.autocrlf=false','apply',...flags,exported],{cwd:review,windowsHide:true});
      assert.equal(await fs.readFile(path.join(review,'hello.txt'),'utf8'),c.after);
      assert.deepEqual(await fs.readdir(repo),[]);
      await mobile.close();mobile=null;
    }
    const mutations=(await fs.readFile(writes,'utf8')).split('\n').filter(Boolean);
    assert.equal(mutations.length,stage==='prepared'?0:1);
    report.push({stage,receipt:row.phase,privateWrites:mutations.length,sharedFiles:0,oldWriterExited:true,terminal:'CANCELLED',bytesMatch:row.bytesMatch});
    await app.close();app=null;page=null;
  }
  console.log(JSON.stringify(report));await fs.writeFile(path.join(output,'report.json'),JSON.stringify(report,null,2));
}catch(error){if(page)console.log('UI',await page.locator('body').innerText({timeout:3000}).catch(()=>''));console.log('API',JSON.stringify(recorded.slice(-15)));throw error;}
finally{if(app)await app.close().catch(()=>{});if(mobile)await mobile.close().catch(()=>{});for(const socket of connections)socket.destroy();await new Promise(resolve=>server.close(resolve));}
// Playwright may retain a pipe for a deliberately killed Electron. The owned
// applications, HTTP server and evidence file have all been awaited above.
process.exit(0);

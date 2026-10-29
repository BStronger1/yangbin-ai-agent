import {_electron as electron} from 'playwright';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),repo=path.dirname(frontend);
const output=process.env.EVIDENCE_DIR || path.join(repo,'desktop/build/acceptance');await fs.mkdir(output,{recursive:true});
const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-desktop-ui-')),dataDir=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-desktop-profile-'));
await fs.writeFile(path.join(root,'index.html'),'<h1>native desktop</h1>');
let project=null,account='desktop-owner',task=null,pending=[],turnId,clientId,previewJob;const automatic=process.env.TEST_POLICY==='allow';const receipts=[];const checks={},errors=[];
const request=(operation,args)=>({requestId:crypto.randomUUID(),operation,arguments:args,turnId,expiresAt:Date.now()+60000,claimed:false});
function approval(toolName,args){task.status='WAITING_APPROVAL';task.events.push({type:'approval_required',turnId,approvalId:toolName,toolName,arguments:args});}
const check=(name,value)=>{assert.ok(value,name);checks[name]=true};
const server=http.createServer(async(req,res)=>{
 const pathname=new URL(req.url,'http://localhost').pathname;let body='';for await(const b of req)body+=b;
 const json=value=>{res.setHeader('content-type','application/json');res.end(JSON.stringify(value));};
 if(pathname==='/api/auth/me')return json({user:{id:account,username:account,role:'admin',csrf:'fixture'}});
 if(pathname==='/api/ai/models')return json({profiles:[],providers:[],systemEnabled:true,defaultModelId:'system'});
 if(pathname==='/api/ai/coding/capabilities')return json({available:true,shellEnabled:false});
 if(pathname==='/api/ai/coding/projects'){
  if(req.method==='POST')project={id:'desktop-project',...JSON.parse(body)};
  return json(req.method==='POST'?project:project?[project]:[]);
 }
 if(pathname==='/api/ai/coding/sessions'){
  if(req.method==='POST'){task={id:'desktop-session',projectId:project.id,title:JSON.parse(body).title,approvalMode:JSON.parse(body).approvalMode,status:'IDLE',revision:0,turns:[],events:[]};return json(task)}return json(task?[task]:[]);
 }
 if(pathname==='/api/ai/coding/sessions/desktop-session')return json(task);
 if(pathname.endsWith('/turns')){const data=JSON.parse(body);turnId=data.requestId;clientId=data.localClientId;task.status='RUNNING';task.approvalMode=data.approvalMode;task.turns=[{id:turnId,prompt:data.prompt,approvalMode:data.approvalMode,status:'RUNNING'}];task.revision++;pending=[request('ping',{})];return json(task);}
 if(pathname.endsWith('/local-files'))return json(pending);
 if(pathname.endsWith('/local-files/claim')){const data=JSON.parse(body),item=pending.find(p=>p.requestId===data.requestId);assert.equal(data.clientId,clientId);item.claimed=true;return json(item);}
 if(pathname.endsWith('/local-files/result')){
  const data=JSON.parse(body),item=pending.find(p=>p.requestId===data.requestId);assert.equal(data.ok,true,data.output);receipts.push({op:item.operation,output:data.output});pending=[];
  if(item.operation==='ping'){assert.equal(JSON.parse(data.output).localProcesses,true);pending=[request('read',{path:'index.html'})];}
  if(item.operation==='read'){const args={path:'index.html',oldText:'written by desktop',newText:'task completed'};if(automatic)pending=[request('edit',args)];else approval('edit',args);}
  if(item.operation==='edit'){if(automatic)pending=[request('start',{command:"Write-Output 'automatic-task-command'"})];else approval('preview',{path:'index.html'});}
  if(item.operation==='start')pending=[request('preview',{path:'index.html'})];
  if(item.operation==='preview'){previewJob=JSON.parse(data.output);pending=[request('process_status',{id:previewJob.id})];}
  if(item.operation==='process_status'){assert.equal(JSON.parse(data.output).state,'RUNNING');task.status='SUCCEEDED';task.turns[0].status='SUCCEEDED';task.events.push({type:'context',turnId,maxOutputTokens:256000,contextWindow:1000000},{type:'text_delta',turnId,delta:'桌面任务已完成'});}
  task.revision++;return json({ok:true});
 }
 if(pathname.includes('/approvals/')){const operation=pathname.split('/').at(-1),event=task.events.find(e=>e.approvalId===operation);task.status='RUNNING';task.events.push({type:'approval_resolved',turnId,approvalId:operation,approved:true});pending=[request(operation,event.arguments)];task.revision++;return json(task);}
 if(pathname.endsWith('/events')){res.setHeader('content-type','text/event-stream');return res.end(': offline\n\n');}
 if(pathname.startsWith('/api/')){res.statusCode=404;return json({message:pathname});}
 let file=path.join(frontend,'dist',pathname);try{if(!(await fs.stat(file)).isFile())throw Error();}catch{file=path.join(frontend,'dist/index.html');}
 res.setHeader('content-type',({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream');res.end(await fs.readFile(file));
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const origin='http://127.0.0.1:'+server.address().port;
let client;
try{
 client=await electron.launch({executablePath:path.join(repo,'desktop/node_modules/electron/dist/electron.exe'),args:[path.join(repo,'desktop/main.mjs')],env:{...process.env,YANGBIN_DEV_ORIGIN:origin,YANGBIN_DEV_DATA:dataDir,YANGBIN_DEV_HIDDEN:'1'},timeout:30000});
 await client.evaluate(({dialog},root)=>{dialog.showOpenDialog=async()=>({canceled:false,filePaths:[root]});globalThis.confirmations=[];dialog.showMessageBox=async(_window,args)=>{globalThis.confirmations.push(args);return {response:1}};},root);
 const page=await client.firstWindow();page.on('pageerror',e=>errors.push(String(e)));page.setDefaultTimeout(15000);
 await page.getByTestId('project-picker').click();await page.getByTestId('add-local-project').click();
 await page.getByTestId('local-status').filter({hasText:'已连接'}).waitFor();
 check('native-project-selection',project?.source==='local');
 check('no-runner-pairing-panel-by-default',await page.getByTestId('local-runner-panel').count()===0);
 check('normal-status-has-no-warning',!await page.getByTestId('local-status').evaluate(e=>e.classList.contains('coding-warning')));
 await page.getByTestId('run-options').click();
 check('built-in-runtime-no-connection-form',await page.getByText('本机执行已内置',{exact:false}).isVisible() && await page.getByRole('button',{name:'连接运行器',exact:true}).count()===0);
 await page.getByTestId('run-options').click();
 const result=await page.evaluate(async()=>{
  const api=window.yangbinDesktop,h=await api.restore('desktop-owner','desktop-project');
  const exec=(op,args={})=>api.execute(h.id,op,args,crypto.randomUUID());
  await exec('read',{path:'index.html'});
  await exec('edit',{path:'index.html',oldText:'native desktop',newText:'written by desktop'});
  await exec('write',{path:'readme.txt',content:'native write'});
  const ping=JSON.parse((await exec('ping')).output);
  const id=crypto.randomUUID(),args={command:"Write-Output 'desktop-real-command'"};
  const command=JSON.parse((await api.execute(h.id,'start',args,id)).output);
  const duplicate=JSON.parse((await api.execute(h.id,'start',args,id)).output);
  let reuseRejected=false;try{await api.execute(h.id,'start',{command:'bad'},id)}catch{reuseRejected=true}
  const preview=JSON.parse((await exec('preview',{path:'index.html'})).output);
  return {id:h.id,ping,command,duplicate,preview,reuseRejected};
 });
 check('direct-write-to-selected-disk',await fs.readFile(path.join(root,'index.html'),'utf8')==='<h1>written by desktop</h1>');
 check('process-capability-advertised',result.ping.localProcesses===true);
 check('duplicate-command-not-reexecuted',result.command.id===result.duplicate.id && result.reuseRejected);
 check('actual-preview-http',await(await fetch(result.preview.url)).text()==='<h1>written by desktop</h1>');
 const job=await page.evaluate(async({id,job})=>{let value;for(let n=0;n<100;n++){value=JSON.parse((await window.yangbinDesktop.execute(id,'process_status',{id:job},crypto.randomUUID())).output);if(value.state!=='RUNNING')return value;await new Promise(r=>setTimeout(r,100))}return value;},{id:result.id,job:result.command.id});
 await fs.writeFile(path.join(output,'command.json'),JSON.stringify(job));
 check('actual-command-exited-successfully',job.exitCode===0 && job.output.includes('desktop-real-command'));
 await client.evaluate(({dialog})=>{dialog.showMessageBox=async()=>({response:0})});
 const denial=await page.evaluate(async id=>{try{await window.yangbinDesktop.execute(id,'start',{command:"Write-Output 'must-not-start'"},crypto.randomUUID());return false}catch{return true}},result.id);
 check('native-command-rejection-honored',denial);
 await client.evaluate(({dialog})=>{dialog.showMessageBox=async()=>({response:1})});
 const stopped=await page.evaluate(async({id,job})=>JSON.parse((await window.yangbinDesktop.execute(id,'stop',{id:job},crypto.randomUUID())).output),{id:result.id,job:result.preview.id});
 check('preview-stopped',stopped.state==='STOPPED');
 await page.reload();await page.getByTestId('local-status').filter({hasText:'已连接'}).waitFor();check('native-binding-restores-without-pairing',true);
 await page.getByTestId('coding-files').click();await page.locator('.coding-file-row').filter({hasText:'readme.txt'}).click();
 check('native-file-panel-reads-original',await page.getByLabel('文件内容预览').textContent()==='native write');
 await page.getByRole('button',{name:'关闭文件面板'}).click();
 await page.getByTestId('coding-file-panel').waitFor({state:'detached'});
 await client.evaluate(({dialog})=>{globalThis.taskPrompts=[];dialog.showMessageBox=async(_w,args)=>{globalThis.taskPrompts.push(args.title);return {response:1}}});
 if(automatic){await page.getByTestId('approval-policy').click();await page.getByTestId('policy-allow').click();}
 await page.getByTestId('coding-prompt').fill('修改 index.html 并启动预览');await page.getByTestId('coding-prompt').press('Enter');
 if(!automatic){await page.getByTestId('approval-allow').click();await page.getByTestId('approval-allow').click();}
 await page.getByText('桌面任务已完成',{exact:true}).waitFor({timeout:30000});
 check('task-rpc-uses-native-files-and-processes',receipts.map(x=>x.op).join(',')===(automatic?'ping,read,edit,start,preview,process_status':'ping,read,edit,preview,process_status'));
 check('task-modified-original-and-preview',await fs.readFile(path.join(root,'index.html'),'utf8')==='<h1>task completed</h1>' && (await(await fetch(previewJob.url)).text()).includes('task completed'));
 if(automatic){const prompts=await client.evaluate(()=>globalThis.taskPrompts);check('one-task-grant-no-per-tool-prompts',prompts.length===1&&prompts[0]==='本轮全部允许');await page.waitForFunction(()=>document.querySelector('[data-testid=approval-policy]').textContent.includes('按需确认'));check('automatic-policy-resets-after-completion',true);}
 const rejected=await page.evaluate(async({id,turnId})=>{try{await window.yangbinDesktop.execute(id,'write',{path:'cancelled.txt',content:'must not write'},crypto.randomUUID(),{sessionId:'desktop-session',turnId,expiresAt:Date.now()+10000});return false}catch{return true}},{id:result.id,turnId});
 check('finished-task-cannot-write-late',rejected && await fs.stat(path.join(root,'cancelled.txt')).catch(()=>null)===null);
 // Hidden Electron windows can return a stale compositor frame despite current
 // DOM state. Present this test-owned window without focus for visual evidence.
 await client.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].showInactive());
 await page.getByText('桌面任务已完成',{exact:true}).waitFor();
 await page.screenshot({path:path.join(output,'desktop.png')});
 await client.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].hide());
 check('renderer-has-no-node',await page.evaluate(()=>typeof window.require==='undefined' && typeof window.process==='undefined'));
 const preferences=await client.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].webContents.getLastWebPreferences());
 check('renderer-isolation',preferences.sandbox && preferences.contextIsolation && !preferences.nodeIntegration && preferences.webSecurity);
 account='different-owner';
 check('other-account-cannot-use-directory',await page.evaluate(async id=>{try{await window.yangbinDesktop.execute(id,'read',{path:'readme.txt'},crypto.randomUUID());return false}catch{return true}},result.id));
 check('no-page-errors',errors.length===0);
 await fs.writeFile(path.join(output,'RESULT.json'),JSON.stringify({status:'PASSED',checks,nativeDialogSelection:'substituted in test; actual main-process files and commands',modelCalls:0},null,2));
 console.log(JSON.stringify({status:'PASSED',checks:Object.keys(checks).length}));
}catch(e){await fs.writeFile(path.join(output,'FAILURE.json'),JSON.stringify({error:String(e),checks,errors,task,pending,receipts,page:client?await(await client.firstWindow()).locator('body').innerText().catch(()=>null):null}));throw e;}
finally{await client?.close();await new Promise(r=>server.close(r));await fs.rm(root,{recursive:true,force:true});await fs.rm(dataDir,{recursive:true,force:true,maxRetries:10,retryDelay:100});}

import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),dist=path.join(frontend,'dist');
const out=path.resolve(process.env.EVIDENCE_DIR || path.join(frontend,'tmp/local-files-ui'));await fs.mkdir(out,{recursive:true});
const checks={},errors=[],unknown=[];const check=(name,value=true)=>{assert.ok(value,name);checks[name]=true;};
const project={id:'local-project',name:'device-project',source:'local'};let created=false,session=null,pending=[],clientId,turnId,approved=false,results=[];
const request=(operation,args)=>({requestId:crypto.randomUUID(),operation,arguments:args,turnId,expiresAt:Date.now()+30000,claimed:false});
const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})});
const page=await browser.newPage({viewport:{width:1440,height:1000}});page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(String(e)));
await page.addInitScript(()=>{
  // Substitute only the native picker UI; handles and writes use actual browser OPFS.
  window.showDirectoryPicker=async()=>{const root=await navigator.storage.getDirectory(),dir=await root.getDirectoryHandle('device-project',{create:true});const file=await dir.getFileHandle('main.txt',{create:true}),w=await file.createWritable();await w.write('before\n');await w.close();return dir;};
});
await page.route('**/*',async route=>{
  const req=route.request(),name=new URL(req.url()).pathname,method=req.method();
  if(name.startsWith('/api/')){
    let data={},status=200;const body=req.postData()?req.postDataJSON():null;
    if(name==='/api/auth/me')data={user:{id:'local-owner',username:'local fixture',role:'admin',csrf:'fixture'}};
    else if(name==='/api/ai/models')data={profiles:[],providers:[],systemEnabled:true,defaultModelId:'system'};
    else if(name==='/api/ai/coding/capabilities')data={available:true,shellEnabled:false};
    else if(name==='/api/ai/coding/projects'&&method==='GET')data=created?[project]:[];
    else if(name==='/api/ai/coding/projects'&&method==='POST'){check('creates-local-source',body.source==='local');created=true;data=project;}
    else if(name==='/api/ai/coding/sessions'&&method==='GET')data=session?[session]:[];
    else if(name==='/api/ai/coding/sessions'&&method==='POST'){session={id:'local-session',projectId:project.id,title:body.title,approvalMode:'on-risk',status:'IDLE',revision:0,turns:[],events:[]};data=session;}
    else if(name==='/api/ai/coding/sessions/local-session')data=session;
    else if(name.endsWith('/turns')){clientId=body.localClientId;turnId=body.requestId;check('turn-binds-browser-client',typeof clientId==='string'&&clientId.length===36);session.status='RUNNING';session.revision++;session.turns=[{id:turnId,prompt:body.prompt,status:'RUNNING'}];pending=[request('ping',{})];data=session;}
    else if(name.endsWith('/local-files')){if(req.headers()['x-local-client']!==clientId){status=409;data={message:'本机目录连接已失效'};}else data=pending;}
    else if(name.endsWith('/local-files/claim')){assert.equal(body.clientId,clientId);const item=pending.find(p=>p.requestId===body.requestId);assert.ok(item&&!item.claimed);item.claimed=true;data=item;}
    else if(name.endsWith('/local-files/result')){
      const item=pending.find(p=>p.requestId===body.requestId);assert.ok(item);assert.equal(body.ok,true,body.output);results.push({op:item.operation,output:body.output});pending=[];
      if(item.operation==='ping')pending=[request('read',{path:'main.txt'})];
      if(item.operation==='read'){assert.equal(body.output,'before\n');session.status='WAITING_APPROVAL';session.events.push({type:'approval_required',turnId,approvalId:'a1',toolName:'edit',arguments:{path:'main.txt',oldText:'before',newText:'after'}});}
      if(item.operation==='edit'){assert.ok(approved);session.status='SUCCEEDED';session.turns[0].status='SUCCEEDED';session.events.push({type:'context',turnId,maxOutputTokens:256000,contextWindow:1000000});session.events.push({type:'text_delta',turnId,delta:'本机修改完成'});}
      data={ok:true};
    }
    else if(name.endsWith('/approvals/a1')){approved=true;session.status='RUNNING';session.events.push({type:'approval_resolved',turnId,approvalId:'a1',approved:true});pending=[request('edit',{path:'main.txt',oldText:'before',newText:'after'})];data=session;}
    else if(name.endsWith('/events'))return route.fulfill({contentType:'text/event-stream',body:': offline\n\n'});
    else {unknown.push(method+' '+name);status=404;}
    return route.fulfill({status,json:data});
  }
  if(name==='/local-directory.js')return route.fulfill({body:await fs.readFile(path.join(frontend,'src/local-directory.js')),contentType:'text/javascript'});
  let file=path.join(dist,name);try{if(!(await fs.stat(file)).isFile())throw Error();}catch{file=path.join(dist,'index.html');}
  return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream'});
});
async function content(){return page.evaluate(async()=>{const root=await navigator.storage.getDirectory(),dir=await root.getDirectoryHandle('device-project');return (await (await dir.getFileHandle('main.txt')).getFile()).text();});}
try {
  await page.goto('http://localhost:19032/coding');await page.getByTestId('project-picker').click();await page.getByTestId('add-local-project').click();
  await page.getByTestId('local-status').filter({hasText:'已连接'}).waitFor();check('picker-handle-stored-and-connected');
  await page.getByTestId('approval-policy').click();
  const teamOption=page.getByTestId('execution-mode').locator('option[value=team]');
  check('local-team-option-disabled',await teamOption.evaluate(e=>e.disabled));
  check('local-allow-policy-disabled',await page.getByTestId('policy-allow').getAttribute('aria-disabled')==='true');
  await page.keyboard.press('Escape');
  await page.getByTestId('coding-prompt').fill('请读取 main.txt，把 before 修改为 after。');await page.getByTestId('coding-prompt').press('Enter');
  await page.getByTestId('approval-allow').waitFor();check('real-file-unchanged-before-approval',await content()==='before\n');
  await page.getByTestId('approval-allow').click();await page.getByText('本机修改完成',{exact:true}).waitFor();
  check('normal-status-is-neutral',await page.getByTestId('local-status').evaluate(e=>!e.classList.contains('coding-warning') && getComputedStyle(e).backgroundColor==='rgba(0, 0, 0, 0)'));
  check('optional-runner-hidden',await page.getByTestId('local-runner-panel').count()===0);
  const budget=page.getByTestId('coding-budget');check('budget-collapsed-by-default',!await budget.evaluate(e=>e.open));
  await budget.locator('summary').click();check('budget-shows-exact-limit',await budget.textContent().then(t=>t.includes('256,000')&&t.includes('1,000,000')));await budget.locator('summary').click();
  await page.getByTestId('run-options').click();check('web-runner-is-optional',await page.getByTestId('local-runner-panel').textContent().then(t=>t.includes('编辑代码无需安装额外程序')));await page.getByTestId('run-options').click();
  await page.screenshot({path:path.join(out,'neutral-status-desktop.png')});
  check('actual-file-api-write-to-original-handle',await content()==='after\n');check('read-write-rpc-order',results.map(r=>r.op).join(',')==='ping,read,edit');
  check('no-server-file-copy-endpoint',!unknown.some(x=>/\/file|\/diff/.test(x)));
  await page.reload();await page.getByTestId('local-status').filter({hasText:'已连接'}).waitFor();check('indexeddb-handle-restores-after-reload',await content()==='after\n');
  const boundary=await page.evaluate(async()=>{
    const {LocalDirectory,localPath}=await import('/local-directory.js');const root=await navigator.storage.getDirectory(),handle=await root.getDirectoryHandle('device-project');const d=new LocalDirectory(handle),results={};
    const fails=async(name,fn)=>{try{await fn();results[name]=false;}catch{results[name]=true;}};
    for(const p of ['../outside','C:/x','.git/config','.env.local','secrets.json','a\\b','CON.txt','x/../y'])await fails('path '+p,()=>localPath(p));
    await fails('overwrite-needs-read',()=>d.write('main.txt','oops'));
    await d.read('main.txt');const f=await handle.getFileHandle('main.txt'),w=await f.createWritable();await w.write('external edit');await w.close();
    await d.read('main.txt',{observe:false});
    await fails('external-edit-conflict',()=>d.write('main.txt','overwrite'));results['external-preserved']=(await f.getFile()).size==='external edit'.length;
    await d.read('main.txt');await fails('cancel-before-write',()=>d.write('main.txt','overwrite',{},()=>false));
    await fails('oversized-rejected',()=>d.write('new.txt','x'.repeat(65537)));
    await fails('protected-directory-root',()=>new LocalDirectory({name:'.ssh'}));
    await fails('unknown-tool-denied',()=>d.execute('bash',{command:'x'}));
    d.disconnect();await fails('revoked-handle-denied',()=>d.read('main.txt'));return results;
  });for(const [name,value] of Object.entries(boundary))check(name,value);
  // A new page must not execute a pending mutation belonging to the previous browser client.
  const receiptsBefore=results.length;
  session.status='RUNNING';pending=[request('write',{path:'main.txt',content:'must not replay'})];
  await page.reload();await page.getByRole('alert').filter({hasText:'本机目录连接已失效'}).waitFor();
  check('reload-refuses-old-mutation',results.length===receiptsBefore && await content()==='external edit');
  session.status='SUCCEEDED';pending=[];
  await page.setViewportSize({width:390,height:844});await page.waitForFunction(()=>document.querySelector('.studio-sidebar')?.getBoundingClientRect().right<=0);await page.screenshot({path:path.join(out,'local-mobile.png'),fullPage:true});
  check('mobile-no-horizontal-overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));check('no-page-errors',errors.length===0);check('no-unknown-requests',unknown.length===0);
  await fs.writeFile(path.join(out,'results.json'),JSON.stringify({status:'PASSED',checks,paidCalls:0,nativePickerAutomated:false,filesystem:'real browser OPFS FileSystemDirectoryHandle',results},null,2));console.log(JSON.stringify({checks:Object.keys(checks).length,status:'PASSED'}));
} catch(error) {await fs.writeFile(path.join(out,'failure.json'),JSON.stringify({error:String(error),checks,errors,unknown,session,pending,results},null,2));await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});throw error;} finally{await browser.close();}

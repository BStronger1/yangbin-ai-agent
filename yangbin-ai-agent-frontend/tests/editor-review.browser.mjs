import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),dist=path.join(frontend,'dist');
const out=path.resolve(process.env.EVIDENCE_DIR||path.join(frontend,'tmp/editor-review'));await fs.mkdir(out,{recursive:true});
const checks={},errors=[],unknown=[],apiCalls=[];const check=(name,condition=true)=>{assert.ok(condition,name);checks[name]=true};
let owner='owner-a',failFiles=false,slowFile=null,slowStarted,releaseSlow;
const projects=[{id:'local-1',name:'本机演示',source:'local'},{id:'server-1',name:'服务端演示',source:'server'}];
const session={id:'server-session',projectId:'server-1',status:'SUCCEEDED',revision:1,title:'审阅改动',turns:[],events:[]};
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_EXECUTABLE_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const context=await browser.newContext({viewport:{width:1440,height:1000},permissions:['clipboard-read','clipboard-write']});
const page=await context.newPage();page.setDefaultTimeout(10000);page.on('pageerror',e=>errors.push(String(e)));
await page.addInitScript(()=>{window.editorLaunches=[];document.addEventListener('click',e=>{const a=e.target.closest('a[href^="vscode:"]');if(a){window.editorLaunches.push(a.href);e.preventDefault();}},true)});
await page.route('**/*',async route=>{
 const req=route.request(),u=new URL(req.url()),p=u.pathname;
 if(p.startsWith('/api/')){
  apiCalls.push({url:req.url(),body:req.postData()});let data={},status=200;
  if(p==='/api/auth/me')data={user:{id:owner,username:owner,role:'user',csrf:'fixture'}};
  else if(p==='/api/ai/models')data={profiles:[],providers:[],defaultModelId:'system',systemEnabled:true};
  else if(p==='/api/ai/coding/capabilities')data={available:true,shellEnabled:false};
  else if(p==='/api/ai/coding/projects')data=projects;
  else if(p==='/api/ai/coding/sessions')data=[session];
  else if(p==='/api/ai/coding/sessions/server-session')data=session;
  else if(p==='/api/ai/coding/projects/server-1/files') {if(failFiles){status=503;data={message:'文件服务暂不可用'}}else data=[{path:'src/a.js'},{path:'src/b.js'},{path:'README.md'}];}
  else if(p==='/api/ai/coding/sessions/server-session/diff')data=[{path:'src/a.js',kind:'modified',before:'before',after:'after'},{path:'deleted.txt',kind:'deleted',before:'removed',after:null}];
  else if(p==='/api/ai/coding/projects/server-1/file'){
   const name=u.searchParams.get('path');if(name===slowFile){slowStarted?.();await new Promise(resolve=>{releaseSlow=resolve})}
   data={path:name,content:name==='src/a.js'?'slow A':'latest B'};
  }else {unknown.push(p);status=404;data={message:'fixture missing'}}
  return route.fulfill({status,json:data});
 }
 let file=path.join(dist,p);try{if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(dist,'index.html')}
 return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream'});
});
try{
 await page.goto('http://localhost:19034/coding?project=local-1');await page.getByTestId('coding-vscode').click();
 const dialog=page.getByRole('dialog',{name:'在 VS Code 中打开项目'});await dialog.waitFor();
 check('initial-path-field-focused',await page.getByTestId('editor-project-path').evaluate(e=>e===document.activeElement));
 await page.getByTestId('editor-project-path').fill('vscode://command/invalid');check('unsafe-url-has-no-launch-link',await page.getByTestId('editor-save-open').count()===0);
 await page.getByTestId('editor-project-path').fill('C:\\Projects\\中文 #demo');const expected='vscode://file/C:/Projects/%E4%B8%AD%E6%96%87%20%23demo/';
 check('correct-encoded-project-folder',await page.getByTestId('editor-save-open').getAttribute('href')===expected);
 await page.getByTestId('editor-save-open').click();check('explicit-external-launch-link',await page.evaluate(()=>window.editorLaunches.length)===1);
 check('does-not-claim-editor-opened',(await dialog.innerText()).includes('已请求打开 VS Code'));
 await page.keyboard.press('Escape');await dialog.waitFor({state:'hidden'});check('escape-restores-opener',await page.getByTestId('coding-vscode').evaluate(e=>e===document.activeElement));
 await page.reload();await page.getByTestId('coding-vscode-settings').waitFor();check('saved-one-click-project-link',await page.getByTestId('coding-vscode').getAttribute('href')===expected);
 await page.getByTestId('coding-vscode-settings').click();await dialog.waitFor();
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'editor-mobile.png'),fullPage:true});
 check('mobile-dialog-contained',await dialog.evaluate(e=>e.getBoundingClientRect().right<=innerWidth&&e.getBoundingClientRect().left>=0));await page.keyboard.press('Escape');
 owner='owner-b';await page.reload();await page.getByTestId('coding-vscode').waitFor();check('account-path-isolation',await page.getByTestId('coding-vscode-settings').count()===0);
 owner='owner-a';await page.goto('http://localhost:19034/coding?session=server-session');await page.getByTestId('coding-files').waitFor();
 await page.getByTestId('coding-vscode').click();await dialog.getByText('此项目保存在服务端，没有与本机文件夹建立连接。',{exact:true}).waitFor();check('server-not-falsely-mapped-to-local',await dialog.locator('a[href^="vscode:"]').count()===0);await page.keyboard.press('Escape');
 await page.setViewportSize({width:1440,height:1000});await page.getByTestId('coding-files').click();await page.locator('.coding-file-row').first().waitFor();
 check('file-list-loaded',await page.locator('.coding-file-row').count()===3);
 await page.getByTestId('file-search').fill('README');check('search-matches-path',await page.locator('.coding-file-row').count()===1);
 await page.getByTestId('file-search').fill('missing');await page.getByText('没有匹配的文件，试试其他关键词。',{exact:true}).waitFor();check('search-empty-state');
 await page.getByTestId('file-search').fill('');await page.getByTestId('changed-only').check();check('changed-filter',await page.locator('.coding-file-row').count()===1);
 check('deleted-change-retained',await page.locator('.coding-diff').count()===2);
 await page.getByTestId('expand-diffs').click();check('expand-all-diffs',await page.locator('.coding-diff[open]').count()===2);
 await page.getByTestId('collapse-diffs').click();check('collapse-all-diffs',await page.locator('.coding-diff[open]').count()===0);
 await page.getByTestId('changed-only').uncheck();
 slowFile='src/a.js';const started=new Promise(resolve=>{slowStarted=resolve});await page.locator('.coding-file-row').filter({hasText:'src/a.js'}).click();await started;
 await page.locator('.coding-file-row').filter({hasText:'src/b.js'}).click();await page.locator('.coding-file-preview pre').filter({hasText:'latest B'}).waitFor();
 const finished=page.waitForResponse(r=>new URL(r.url()).searchParams.get('path')==='src/a.js');releaseSlow();await finished;
 // Flush the response and rendering, without relying on guessed network timing.
 await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 check('late-preview-cannot-overwrite-selection',await page.locator('.coding-file-preview summary').innerText()==='src/b.js');
 await page.getByTestId('copy-file-content').click();await page.getByText('已复制文件内容',{exact:true}).waitFor();check('copy-preview',await page.evaluate(()=>navigator.clipboard.readText())==='latest B');
 failFiles=true;await page.getByTestId('refresh-files').click();await page.getByRole('alert').filter({hasText:'文件服务暂不可用'}).waitFor();check('refresh-error-retains-labelled-list',await page.locator('.coding-file-row').count()===3);
 failFiles=false;await page.getByTestId('refresh-files').click();await page.getByRole('alert').filter({hasText:'文件服务暂不可用'}).waitFor({state:'hidden'});check('refresh-error-recovers');
 await page.getByTestId('expand-diffs').click();await page.screenshot({path:path.join(out,'review-desktop.png'),fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'review-mobile.png'),fullPage:true});check('mobile-no-document-overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 check('path-never-sent-to-api',!JSON.stringify(apiCalls).includes('Projects'));
 check('no-unexpected-api',unknown.length===0);check('no-browser-errors',errors.length===0);
}finally{await fs.writeFile(path.join(out,'results.json'),JSON.stringify({checks,errors,unknown},null,2));await browser.close()}
console.log(JSON.stringify({passed:Object.keys(checks).length,out}));

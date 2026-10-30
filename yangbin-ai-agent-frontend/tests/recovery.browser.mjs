import {chromium} from 'playwright';import fs from 'node:fs/promises';import path from 'node:path';import assert from 'node:assert/strict';
const frontend=path.resolve(import.meta.dirname,'..'),out=process.env.EVIDENCE_DIR||path.join(frontend,'tmp/recovery-ui');await fs.mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_EXECUTABLE_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const page=await browser.newPage();const errors=[],checks={};page.on('pageerror',e=>errors.push(String(e)));let submissions=0,accepts=0,token='first',stale=true;
const session={id:'s1',projectId:'p1',title:'中断恢复',revision:1,status:'FAILED',approvalMode:'allow',turns:[{id:'old',prompt:'修改文件',status:'FAILED',recoveryRequired:true}],events:[]};
const report=()=>({required:true,accepted:accepts>0,canAccept:true,token,scope:'SERVER_FILE_WRITE_EDIT',message:'检查后接受当前文件，不重放旧任务。',errors:[],operations:[{operationId:'op',sessionId:'s1',turnId:'old',path:'main.txt',state:'PREPARED',observation:'AFTER_MATCH'}]});
await page.route('**/*',async route=>{
 const req=route.request(),url=new URL(req.url()),p=url.pathname;let data={};
 if(p.startsWith('/api/')){
  if(p==='/api/auth/me')data={user:{id:'owner',username:'test',role:'admin',csrf:'fixture'}};
  else if(p.endsWith('/models'))data={profiles:[],providers:[],systemEnabled:true,defaultModelId:'system'};
  else if(p.endsWith('/capabilities'))data={available:true};
  else if(p==='/api/ai/coding/projects')data=[{id:'p1',name:'project',source:'server'}];
  else if(p==='/api/ai/coding/sessions')data=[session];
  else if(p==='/api/ai/coding/sessions/s1')data=session;
  else if(p.endsWith('/recovery/accept')){const body=req.postDataJSON();assert.equal(body.acceptCurrentFiles,true);assert.equal(body.token,token);if(stale){stale=false;token='changed';return route.fulfill({status:409,json:{message:'文件已变化'}})}accepts++;data=report();}
  else if(p.endsWith('/recovery'))data=report();
  else if(p.endsWith('/turns')){assert.equal(accepts,1);const body=req.postDataJSON();assert.notEqual(body.requestId,'old');assert.equal(body.prompt,'请检查当前文件后继续');submissions++;session.turns[0].recoveryRequired=false;session.turns.push({id:body.requestId,prompt:body.prompt,status:'SUCCEEDED'});session.status='SUCCEEDED';data=session;}
  else return route.fulfill({status:404,json:{}});
  return route.fulfill({json:data});
 }
 let file=path.join(frontend,'dist',p);try{if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(frontend,'dist/index.html')}
 return route.fulfill({body:await fs.readFile(file),contentType:({'.js':'text/javascript','.css':'text/css','.html':'text/html'})[path.extname(file)]||'application/octet-stream'});
});
try{
 await page.goto('http://localhost:19041/coding?session=s1');await page.getByTestId('accept-recovery').waitFor();await page.getByTestId('coding-prompt').fill('请检查当前文件后继续');
 checks['submit-blocked-before-review']=await page.getByTestId('coding-send').isDisabled();assert.ok(checks['submit-blocked-before-review']);assert.equal(submissions,0);
 assert.match(await page.getByTestId('coding-recovery').textContent(),/预期修改一致/);checks['actual-content-and-missing-receipt-distinguished']=true;
 await page.getByTestId('accept-recovery').click();await page.getByRole('alert').filter({hasText:'文件状态已变化'}).waitFor();assert.ok(await page.getByTestId('coding-send').isDisabled());checks['stale-acceptance-does-not-unblock']=true;
 await page.getByRole('button',{name:'重新核对',exact:true}).click();await page.getByTestId('accept-recovery').click();await page.getByText('已接受当前文件状态。',{exact:false}).waitFor();
 await page.getByTestId('coding-send').click();await page.waitForFunction(()=>!document.querySelector('[data-testid="coding-recovery"]'));assert.equal(submissions,1);checks['explicit-review-starts-one-new-turn-without-replay']=true;
 assert.deepEqual(errors,[]);checks['no-page-errors']=true;await page.screenshot({path:path.join(out,'continued.png')});
 await fs.writeFile(path.join(out,'RESULT.json'),JSON.stringify({status:'PASSED',checks,modelCalls:0},null,2));console.log(JSON.stringify(checks));
}catch(error){await fs.writeFile(path.join(out,'FAILED.json'),JSON.stringify({error:String(error),errors,checks},null,2));throw error}finally{await browser.close()}

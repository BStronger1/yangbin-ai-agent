import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),root=path.join(frontend,'dist')
const out=path.resolve(process.env.EVIDENCE_DIR||path.join(frontend,'tmp/memory-candidates-ui'))
await fs.mkdir(out,{recursive:true})
const checks={},errors=[],unknown=[],decisions=[]
let revision=7,hold=false,release,conflict=false
const candidate=(id,sourceType,extra={})=>({id,key:'候选 '+id,content:'合成候选内容 '+id,kind:'FACT',sourceType,sourceSessionId:'session-source',sourceTurnId:'turn-source',createdAt:'2026-10-22T00:00:00Z',expiresAt:'2099-01-01T00:00:00Z',uncertainty:sourceType==='USER_EXPLICIT'?'EXTRACTED_NEEDS_CONFIRMATION':'UNVERIFIED_NOT_USER_FACT',status:'PENDING',targetId:null,version:1,targetVersion:0,...extra})
const candidates=[candidate('user','USER_EXPLICIT'),candidate('model','MODEL_REFLECTION',{content:'<img src=x onerror="window.candidateXss=true">'}),candidate('tool','TOOL_OBSERVATION'),candidate('expired','USER_EXPLICIT',{expiresAt:'2020-01-01T00:00:00Z'}),candidate('correct','USER_EXPLICIT',{targetId:'existing-memory',targetVersion:3}),candidate('correct-ok','USER_EXPLICIT',{targetId:'other-memory',targetVersion:5})]
const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})})
const page=await browser.newPage({viewport:{width:1440,height:1000}})
page.setDefaultTimeout(10000);page.on('pageerror',error=>errors.push(String(error)))
await page.route('**/*',async route=>{
 const req=route.request(),url=new URL(req.url()),name=url.pathname,method=req.method()
 if(name.startsWith('/api/')){
  let data={},status=200
  if(name==='/api/auth/me')data={user:{id:'candidate-owner',username:'候选离线验收',role:'admin',csrf:'fixture'}}
  else if(name==='/api/ai/models')data={profiles:[],providers:[],systemEnabled:true,defaultModelId:'system'}
  else if(name==='/api/ai/coding/capabilities')data={available:true,shellEnabled:false}
  else if(name==='/api/ai/coding/projects')data=[{id:'project-a',name:'项目 A'},{id:'project-b',name:'项目 B'}]
  else if(name==='/api/ai/coding/sessions')data=[]
  else if(name.endsWith('/memory'))data={revision,enabled:true,autoExtract:false,items:[]}
  else if(name.endsWith('/memory/candidates'))data={revision,candidates:name.includes('/project-b/')?[]:candidates.filter(c=>c.status==='PENDING')}
  else if(name.includes('/memory/candidates/')&&name.endsWith('/decision')&&method==='POST'){
   const input=req.postDataJSON(),id=name.split('/').at(-2),item=candidates.find(c=>c.id===id);decisions.push({id,...input})
   if(hold)await new Promise(resolve=>{release=resolve})
   if(conflict){conflict=false;revision++;status=409;data={message:'revision conflict'}}
   else if(input.revision!==revision||input.version!==item.version||input.targetVersion!==item.targetVersion){status=409}
   else if(input.accept&&(item.sourceType!=='USER_EXPLICIT'||Date.parse(item.expiresAt)<=Date.now())){status=409}
   else{item.status=input.accept?'ACCEPTED':'REJECTED';item.version++;revision++;data={status:item.status,revision}}
  }else{unknown.push(method+' '+name);status=404}
  return route.fulfill({status,json:data})
 }
 let file=path.join(root,name);try{if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(root,'index.html')}
 return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream'})
})
const check=(name,value=true)=>{assert.ok(value,name);checks[name]=true}
const open=async()=>{await page.getByTestId('coding-memory-open').click();await page.getByTestId('memory-candidates').waitFor();await page.waitForFunction(()=>!document.querySelector('[data-testid="candidates-refresh"]').disabled)}
const idle=async()=>page.waitForFunction(()=>!document.querySelector('[data-testid="candidates-refresh"]').disabled)
try{
 await page.goto('http://candidate-fixture.test/coding');await open()
 check('candidate-source-and-uncertainty-visible',(await page.getByTestId('candidate-user').innerText()).includes('用户明确表达')&&(await page.getByTestId('candidate-model').innerText()).includes('未经核实，不是已确认的用户事实'))
 check('timestamps-and-provenance-visible',(await page.getByTestId('candidate-user').innerText()).includes('2099')&&(await page.getByTestId('candidate-user').innerText()).includes('session-source / turn-source'))
 check('target-version-visible',(await page.getByTestId('candidate-correct').innerText()).includes('目标版本 v3'))
 check('nonuser-accept-disabled',await page.getByTestId('candidate-accept-model').isDisabled()&&await page.getByTestId('candidate-accept-tool').isDisabled())
 check('expired-accept-disabled',await page.getByTestId('candidate-accept-expired').isDisabled())
 check('candidate-content-escaped',await page.evaluate(()=>!window.candidateXss&&!document.querySelector('.candidate-content img')))
 hold=true;await page.getByTestId('candidate-accept-user').click();await page.waitForFunction(()=>document.querySelector('[data-testid="candidate-reject-model"]').disabled)
 await page.keyboard.press('Escape');check('pending-decision-blocks-close-and-double-submit',await page.getByTestId('coding-memory').isVisible()&&await page.getByTestId('candidate-accept-user').isDisabled());release();hold=false
 await page.getByTestId('candidate-user').waitFor({state:'hidden'});await idle();check('explicit-user-accepted',candidates[0].status==='ACCEPTED'&&decisions.length===1)
 check('exact-decision-version-contract',decisions[0].revision===7&&decisions[0].version===1&&decisions[0].targetVersion===0&&decisions[0].accept===true)
 await page.getByTestId('candidate-reject-model').click();await page.getByTestId('candidate-model').waitFor({state:'hidden'});await idle();check('model-observation-rejected',decisions.at(-1).id==='model'&&decisions.at(-1).accept===false)
 conflict=true;const before=decisions.length;await page.getByTestId('candidate-accept-correct').click();await page.getByTestId('memory-candidates').getByRole('alert').waitFor();await idle()
 check('conflict-retains-candidate-and-refreshes',(await page.getByTestId('candidate-correct').innerText()).includes('目标版本 v3')&&(await page.getByTestId('memory-candidates').getByRole('alert').innerText()).includes('不会自动重复提交'))
 check('conflict-does-not-replay',decisions.length===before+1&&candidates.find(c=>c.id==='correct').status==='PENDING')
 await page.getByTestId('candidate-reject-correct').click();await page.getByTestId('candidate-correct').waitFor({state:'hidden'});await idle();check('explicit-decision-after-refresh-uses-new-revision',decisions.at(-1).revision===10&&decisions.at(-1).targetVersion===3&&!decisions.at(-1).accept)
 await page.getByTestId('candidate-reject-tool').click();await page.getByTestId('candidate-tool').waitFor({state:'hidden'});await idle();check('tool-observation-rejected',decisions.at(-1).id==='tool'&&!decisions.at(-1).accept)
 await page.getByTestId('candidate-accept-correct-ok').click();await page.getByTestId('candidate-correct-ok').waitFor({state:'hidden'});await idle();check('explicit-correction-preserves-target-version',decisions.at(-1).id==='correct-ok'&&decisions.at(-1).targetVersion===5&&decisions.at(-1).accept)
 check('no-candidate-browser-storage',await page.evaluate(()=>!JSON.stringify({local:{...localStorage},session:{...sessionStorage}}).includes('合成候选内容')))
 await page.setViewportSize({width:390,height:844});check('mobile-no-overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:path.join(out,'candidates-mobile.png')})
 await page.getByLabel('关闭项目记忆').click();await page.getByTestId('coding-memory').waitFor({state:'hidden'});await page.getByTestId('project-picker').click();await page.getByRole('button',{name:'项目 B',exact:true}).click();await open();await page.getByTestId('candidates-empty').waitFor()
 check('project-switch-clears-candidates',await page.getByTestId('candidate-expired').count()===0)
 check('no-page-errors',errors.length===0);check('no-unknown-api',unknown.length===0)
}catch(error){await fs.writeFile(path.join(out,'failure.json'),JSON.stringify({error:String(error),checks,errors,unknown,decisions},null,2));await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});throw error}
finally{await fs.writeFile(path.join(out,'report.json'),JSON.stringify({checks,errors,unknown,decisions,passed:Object.values(checks).filter(Boolean).length,paidModelCalls:0,scope:'Real Chrome offline API contract; not backend authorization proof.'},null,2));await browser.close()}
console.log(JSON.stringify(checks))

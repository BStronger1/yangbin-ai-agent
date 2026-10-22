import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const root = path.join(projectRoot, 'dist')
const out = path.resolve(process.env.EVIDENCE_DIR || path.join(projectRoot, 'tmp/memory-ui'))
await fs.mkdir(out, { recursive: true })
const checks = {}, errors = [], unknown = [], writes = []
let owner = 'owner-a', failCreate = false, conflict = false, rejectContent = false, slowList = false, releaseList = null
const projects = [{id:'project-a',name:'项目 A'},{id:'project-b',name:'项目 B'}]
const stores = new Map(), requests = new Map()
const store = p => {const key = owner + ':' + p; if (!stores.has(key)) stores.set(key,{enabled:true,autoExtract:false,revision:0,items:[]}); return stores.get(key)}
const publicState = s => ({...s,items:s.items.filter(i => i.status !== 'FORGOTTEN')})
const stamp = '2026-10-22T05:00:00Z'
const browser = await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH ? {executablePath:process.env.CHROME_EXECUTABLE_PATH} : {})})
const page = await browser.newPage({viewport:{width:1440,height:1000}})
page.setDefaultTimeout(12000)
page.on('pageerror', e => errors.push(String(e)))
await page.route('**/*', async route => {
 const req = route.request(), url = new URL(req.url()), name = url.pathname, method = req.method()
 if (name.startsWith('/api/')) {
  let data = {}, status = 200
  if (name === '/api/auth/me') data = {user:{id:owner,username:'离线记忆验收',role:'admin',csrf:'fixture'}}
  else if (name === '/api/ai/models') data = {profiles:[],providers:[],defaultModelId:'system',systemEnabled:true}
  else if (name === '/api/ai/coding/capabilities') data = {available:true,shellEnabled:false}
  else if (name === '/api/ai/coding/projects') data = projects
  else if (name === '/api/ai/coding/sessions') data = []
  else if (/^\/api\/ai\/coding\/projects\/[^/]+\/memory/.test(name)) {
   const pieces = name.split('/'), project = pieces[5], operation = pieces[7], s = store(project), input = ['POST','PUT'].includes(method) ? req.postDataJSON() : null
   if (method !== 'GET') writes.push({method,project,operation,input,version:url.searchParams.get('version')})
   if (operation === 'search') {const q=url.searchParams.get('q');const items=s.enabled?s.items.filter(i=>i.status==='ACTIVE'&&(i.key+i.content).includes(q)):[];data={items,characters:items.reduce((n,i)=>n+i.content.length,0),budgetChars:2048}}
   else if (method === 'GET') {data=structuredClone(publicState(s)); if (slowList) {slowList=false; await new Promise(resolve=>{releaseList=resolve})}}
   else if (operation === 'settings') {if(input.revision!==s.revision){status=409}else{Object.assign(s,input,{revision:s.revision+1});data=publicState(s)}}
   else if (rejectContent) {rejectContent=false;status=400;data={message:'sensitive value must not be echoed'}}
   else if (method === 'POST') {
    const requestKey=owner+':'+project+':'+input.requestId
    if(requests.has(requestKey))data=requests.get(requestKey)
    else {data={id:'m'+(s.items.length+1),...input,version:1,status:'ACTIVE',createdAt:stamp,updatedAt:stamp};s.items.push(data);requests.set(requestKey,data);s.revision++}
    if (failCreate) {failCreate=false;return route.abort('failed')}
   } else {
    const item=s.items.find(i=>i.id===operation), version=method==='DELETE'?Number(url.searchParams.get('version')):input.version
    if(conflict){conflict=false;item.version++;item.content='服务器中的最新版本';s.revision++;status=409;data={message:'conflict'}}
    else if(!item || version!==item.version){status=409;data={message:'conflict'}}
    else if(method==='DELETE'){item.content='';item.status='FORGOTTEN';item.version++;s.revision++;data={id:item.id,status:item.status,version:item.version}}
    else {Object.assign(item,input,{version:item.version+1,updatedAt:stamp});s.revision++;data=item}
   }
  } else {unknown.push(method+' '+name);status=404;data={message:'unknown offline fixture API'}}
  return route.fulfill({status,json:data})
 }
 let file=path.join(root,name);try{if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(root,'index.html')}
 return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream'})
})
const open = async () => {await page.getByTestId('coding-memory-open').click();await page.getByTestId('memory-key').waitFor();await page.waitForFunction(()=>!document.querySelector('[data-testid="memory-refresh"]').disabled)}
const idle = async () => page.waitForFunction(()=>document.querySelector('[data-testid="memory-refresh"]')&&!document.querySelector('[data-testid="memory-refresh"]').disabled)
const fill = async (key, content) => {await page.getByTestId('memory-key').fill(key);await page.getByTestId('memory-content').fill(content)}
const save = async () => {await page.getByTestId('memory-save').click();await page.getByText('记忆已保存，可在本项目的新会话中按需取回。',{exact:true}).waitFor();await idle()}
try {
 await page.goto('http://memory-fixture.test/coding');await open()
 checks.codingEntry=true;checks.autoExtractDefaultOff=!(await page.getByTestId('memory-auto-extract').isChecked());checks.scopeCopy=(await page.getByTestId('coding-memory').innerText()).includes('仅当前账号的 Coding 项目')
 await fill('测试框架','使用 Vitest');await page.getByTestId('memory-kind').selectOption('CONSTRAINT');await save()
 checks.explicitSaved=store('project-a').items[0].content==='使用 Vitest'&&writes.at(-1).input.requestId.length===36
 await page.getByLabel('关闭项目记忆').click();await page.reload();await open();checks.newPageRetrieval=await page.getByTestId('memory-item-m1').getByText('使用 Vitest',{exact:true}).isVisible()
 await page.getByTestId('memory-query').fill('Vitest');await page.getByTestId('memory-search').click();await page.getByText(/检索结果 ·/).waitFor();await idle();checks.searchBudgetVisible=(await page.getByTestId('coding-memory').innerText()).includes('/ 2048 字符')
 await page.getByTestId('memory-edit-m1').click();await page.getByTestId('memory-content').fill('使用 Playwright');await page.getByTestId('memory-save').click();await page.getByText('更正已保存，后续任务将使用有效版本。',{exact:true}).waitFor();await idle()
 checks.optimisticEdit=writes.at(-1).input.version===1&&store('project-a').items[0].version===2
 await page.getByTestId('memory-query').fill('Vitest');await page.getByTestId('memory-search').click();await page.getByTestId('memory-empty').waitFor();await idle();checks.oldVersionAbsent=await page.getByTestId('memory-item-m1').count()===0
 await page.getByTestId('memory-refresh').click();await idle();await page.getByTestId('memory-archive-m1').click();await page.getByText('记忆已归档，不再参与召回；可随时恢复。',{exact:true}).waitFor();await idle();checks.archivedHidden=await page.getByTestId('memory-item-m1').count()===0
 await page.getByTestId('memory-archived').click();await page.getByTestId('memory-archive-m1').click();await page.getByText('记忆已恢复，可再次召回。',{exact:true}).waitFor();await idle();await page.getByRole('button',{name:'有效记忆',exact:true}).click();checks.restoreActive=await page.getByTestId('memory-item-m1').isVisible()
 await page.getByTestId('memory-edit-m1').click();await page.getByTestId('memory-content').fill('本地过期编辑');conflict=true;await page.getByTestId('memory-save').click();await page.getByRole('alert').waitFor();await idle();checks.conflictRefresh=(await page.getByTestId('memory-item-m1').innerText()).includes('服务器中的最新版本')&&await page.getByTestId('memory-content').inputValue()===''
 await page.getByTestId('memory-forget-m1').click();checks.forgetConfirmation=(await page.getByRole('alertdialog').innerText()).includes('测试框架')&&store('project-a').items[0].status==='ACTIVE'
 await page.getByTestId('memory-confirm-forget').click();await page.getByText('已忘记该条目，后续不会再召回。已发送的历史对话不会因此删除。',{exact:true}).waitFor();await idle();checks.forgottenBodyRemoved=store('project-a').items[0].content===''&&await page.getByTestId('memory-item-m1').count()===0
 await page.getByTestId('memory-query').fill('测试框架');await page.getByTestId('memory-search').click();await page.getByTestId('memory-empty').waitFor();await idle();checks.forgottenNotRetrieved=await page.getByTestId('memory-item-m1').count()===0
 await page.getByTestId('memory-enabled').uncheck();await page.getByText('项目记忆设置已更新，从后续任务生效。',{exact:true}).waitFor();await idle();checks.disabledAutoControl=await page.getByTestId('memory-auto-extract').isDisabled();checks.settingsRevision=Number.isInteger(writes.at(-1).input.revision)
 await page.getByTestId('memory-enabled').check();await idle();await page.getByTestId('memory-auto-extract').check();await idle();checks.autoExtractOptIn=store('project-a').autoExtract===true
 await fill('说明','<img src=x onerror="window.memoryXss=true">');await save();checks.contentEscaped=await page.evaluate(()=>!window.memoryXss&&document.querySelectorAll('.memory-content img').length===0)
 await fill('被拒绝条目','fixture credential rejection');rejectContent=true;await page.getByTestId('memory-save').click();await page.getByRole('alert').waitFor();await idle();checks.rejectionNoEcho=!(await page.getByRole('alert').innerText()).includes('sensitive value')&&store('project-a').items.length===2
 await fill('可重试条目','响应断开仍不重复保存');failCreate=true;await page.getByTestId('memory-save').click();await page.getByRole('alert').waitFor();await idle();const firstId=writes.at(-1).input.requestId;checks.failedRequestRetainsDraft=await page.getByTestId('memory-content').inputValue()==='响应断开仍不重复保存';await save();checks.retryStableRequestId=writes.at(-1).input.requestId===firstId&&store('project-a').items.length===3
 await page.screenshot({path:path.join(out,'memory-desktop.png'),fullPage:true})
 slowList=true;await page.getByTestId('memory-refresh').click();await page.waitForTimeout(100);await page.getByLabel('关闭项目记忆').click();await page.getByTestId('project-picker').click();await page.getByRole('button',{name:'项目 B',exact:true}).click();await open();releaseList?.();await page.waitForTimeout(100)
 checks.projectSwitchClears=(await page.getByTestId('coding-memory').innerText()).includes('项目 B')&&await page.getByTestId('memory-item-m2').count()===0&&await page.getByTestId('memory-key').inputValue()===''
 await fill('项目 B 约束','只属于 B');await save();checks.projectWriteScoped=writes.at(-1).project==='project-b'
 owner='owner-b';await page.reload();await open();checks.accountPageIsolated=await page.getByTestId('memory-item-m1').count()===0&&await page.getByTestId('memory-key').inputValue()===''
 checks.noMemoryBrowserStorage=await page.evaluate(()=>!JSON.stringify({local:{...localStorage},session:{...sessionStorage}}).includes('响应断开仍不重复保存'))
 await page.setViewportSize({width:390,height:844});checks.mobileNoOverflow=await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth);const box=await page.getByTestId('coding-memory').boundingBox();checks.mobileDialogVisible=box.x>=0&&box.x+box.width<=390&&box.y>=0&&box.y+box.height<=844;await page.screenshot({path:path.join(out,'memory-mobile.png'),fullPage:true})
 await page.keyboard.press('Escape');checks.escapeCloses=await page.getByTestId('coding-memory').count()===0
 await page.goto('http://memory-fixture.test/super-agent');checks.legacyEntryExcluded=await page.getByTestId('coding-memory-open').count()===0
 checks.noPageErrors=errors.length===0;checks.noUnknownApi=unknown.length===0
 const report={fixture:true,paidModelCalls:0,scope:'UI contract only; not backend isolation or model quality',checks,errors,unknown,passed:Object.values(checks).filter(Boolean).length,total:Object.keys(checks).length}
 await fs.writeFile(path.join(out,'browser-report.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));assert.ok(Object.values(checks).every(Boolean))
} catch(error) {await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});await fs.writeFile(path.join(out,'browser-failure.json'),JSON.stringify({error:String(error),checks,errors,unknown,body:await page.locator('body').innerText()},null,2));throw error} finally {await browser.close()}

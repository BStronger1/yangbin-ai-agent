import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const root = path.join(projectRoot, 'dist')
const out = path.resolve(process.env.EVIDENCE_DIR || path.join(projectRoot, 'tmp/overlays-ui'))
await fs.mkdir(out, { recursive: true })
const checks = {}, errors = [], unknown = [], writes = []
let holdSave = false, releaseSave, conflict = false
const stamp = '2026-10-22T08:00:00Z'
const memory = { enabled:true,autoExtract:false,revision:1,items:[{id:'m1',key:'测试约束',content:'采用离线测试',kind:'FACT',version:1,status:'ACTIVE',updatedAt:stamp}] }
const catalog = {providers:[{id:'openai',name:'测试服务',defaultBaseUrl:'https://fixture.invalid/v1'}],connections:[{id:'c1',name:'连接一',provider:'openai',baseUrl:'https://one.invalid/v1',hasKey:true},{id:'c2',name:'连接二',provider:'openai',baseUrl:'https://two.invalid/v1',hasKey:true}],profiles:[{id:'legacy',name:'旧版模型',provider:'openai',baseUrl:'https://fixture.invalid/v1',model:'fixture',hasKey:true,codingSupported:true}],systemEnabled:true,defaultModelId:'system'}
catalog.allProfiles = catalog.profiles
const session = {id:'s1',projectId:'project-a',title:'待审批测试任务',status:'WAITING_APPROVAL',revision:1,approvalMode:'on-risk',turns:[{id:'t1',prompt:'写入测试文件',status:'WAITING_APPROVAL'}],events:[{seq:1,turnId:'t1',type:'approval_required',approvalId:'ap1',toolName:'write',arguments:{path:'review.txt',content:'fixture'},reason:'需要修改文件'}]}
const browser = await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH ? {executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})})
const page = await browser.newPage({viewport:{width:1440,height:1000}})
page.setDefaultTimeout(10000)
page.on('pageerror', error => errors.push(String(error)))
await page.route('**/*', async route => {
 const req=route.request(), url=new URL(req.url()), name=url.pathname, method=req.method()
 if (name.startsWith('/api/')) {
  let data={},status=200
  if(method!=='GET') writes.push({name,method,input:req.postDataJSON()})
  if(name==='/api/auth/me') data={user:{id:'overlay-owner',username:'离线键盘验收',role:'admin',csrf:'fixture'}}
  else if(name==='/api/ai/models') data=catalog
  else if(name==='/api/ai/coding/capabilities') data={available:true,shellEnabled:false}
  else if(name==='/api/ai/coding/projects') data=[{id:'project-a',name:'项目 A'},{id:'project-b',name:'项目 B'}]
  else if(name==='/api/ai/coding/sessions') data=[session]
  else if(name==='/api/ai/coding/sessions/s1') data=session
  else if(name==='/api/ai/coding/sessions/s1/events') return route.fulfill({contentType:'text/event-stream',body:': offline fixture\n\n'})
  else if(name.includes('/sessions/s1/approvals/')) {session.status='CANCELLED';session.events.push({seq:2,turnId:'t1',type:'approval_resolved',approvalId:'ap1'});data=session}
  else if(name.endsWith('/files')||name.endsWith('/diff')) data=[]
  else if(name.endsWith('/memory')&&method==='GET') data=memory
  else if(name.endsWith('/memory')&&method==='POST') {
   if(holdSave) await new Promise(resolve=>{releaseSave=resolve})
   const input=req.postDataJSON();data={...input,id:'m2',version:1,status:'ACTIVE'};memory.items.push(data);memory.revision++
  } else if(name.endsWith('/memory/m1')&&method==='PUT') {
   if(conflict){conflict=false;status=409;memory.items[0].version++;memory.items[0].content='服务器最新值'}
   else Object.assign(memory.items[0],req.postDataJSON())
   data=memory.items[0]
  } else if(name.startsWith('/ai/never')) {}
  else if(name==='/api/ai/work/catalog') data={partners:[],skills:[]}
  else if(name.includes('/assistant/partners/')&&name.endsWith('/conversations'))data=[]
  else {unknown.push(method+' '+name);status=404;data={message:'Unknown offline fixture'}}
  return route.fulfill({status,json:data})
 }
 let file=path.join(root,name)
 try {if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(root,'index.html')}
 return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream'})
})
const check=(name,value=true)=>{assert.ok(value,name);checks[name]=true}
const gone=async selector=>page.locator(selector).waitFor({state:'hidden'})
const focused=async selector=>page.locator(selector).evaluate(el=>el===document.activeElement)
const within=async selector=>page.locator(selector).evaluate(el=>el.contains(document.activeElement))
const escape=()=>page.keyboard.press('Escape')
const discard=async()=>{await page.getByTestId('discard-changes').click();await gone('[data-testid="unsaved-confirm"]')}
async function memoryOpen(){await page.getByTestId('coding-memory-open').click();await page.getByTestId('memory-key').waitFor();await page.waitForFunction(()=>!document.querySelector('[data-testid="memory-refresh"]').disabled)}
async function trap(selector) {
 const nodes=page.locator(selector).locator('button:visible:not(:disabled),input:visible:not(:disabled),textarea:visible:not(:disabled),select:visible:not(:disabled),a[href]:visible')
 await nodes.last().focus();await page.keyboard.press('Tab');check('tab-wrap-'+Object.keys(checks).length,await focusedSelector(nodes.first()))
 await nodes.first().focus();await page.keyboard.press('Shift+Tab');assert.ok(await focusedSelector(nodes.last()))
}
const focusedSelector=locator=>locator.evaluate(el=>el===document.activeElement)
try {
 await page.goto('http://overlay-fixture.test/coding');await page.getByTestId('project-picker').waitFor()
 await page.getByTestId('coding-history').click();await page.getByTestId('coding-history-panel').waitFor()
 await page.getByTestId('project-picker').click();await page.getByRole('dialog',{name:'选择项目',exact:true}).waitFor()
 await page.getByTestId('model-picker').click();await page.getByRole('dialog',{name:'选择模型',exact:true}).waitFor()
 await escape();check('escape-only-top-model',await page.getByRole('dialog',{name:'选择项目',exact:true}).isVisible()&&await page.getByTestId('coding-history-panel').isVisible())
 await trap('.project-popover')
 await page.getByLabel('新项目名称').fill('未完成项目')
 await page.getByLabel('新项目名称').dispatchEvent('compositionstart')
 await escape();check('composition-escape-keeps-layer',await page.getByRole('dialog',{name:'选择项目',exact:true}).isVisible()&&await page.getByTestId('unsaved-confirm').count()===0)
 await page.getByLabel('新项目名称').dispatchEvent('keydown',{key:'Enter',isComposing:true,keyCode:229})
 check('ime-enter-no-project-write',writes.length===0)
 await page.getByLabel('新项目名称').dispatchEvent('compositionend')
 await escape();await page.getByTestId('unsaved-confirm').waitFor();await trap('[data-testid="unsaved-confirm"]')
 await escape();check('nested-escape-keeps-draft',await page.getByLabel('新项目名称').inputValue()==='未完成项目')
 await escape();await discard();await gone('.project-popover');check('history-under-project-survives',await page.getByTestId('coding-history-panel').isVisible())
 await escape();await gone('[data-testid="coding-history-panel"]')
 for(let i=0;i<3;i++){await page.getByTestId('model-picker').click();await escape();await gone('.model-picker-popover');check('repeat-model-focus-'+i,await focused('[data-testid="model-picker"]'))}
 await page.getByTestId('approval-policy').click();await trap('.policy-popover');await page.getByLabel('关闭审批策略').click();check('policy-explicit-close-focus',await focused('[data-testid="approval-policy"]'))
 await memoryOpen();await trap('[data-testid="coding-memory"]')
 await page.getByTestId('memory-forget-m1').click();await page.getByRole('alertdialog').waitFor();await trap('.memory-confirm');await escape()
 check('memory-confirm-escape-no-forget',await page.getByTestId('coding-memory').isVisible()&&!writes.some(w=>w.method==='DELETE'))
 check('forget-focus-restored',await focused('[data-testid="memory-forget-m1"]'))
 await page.getByTestId('memory-content').fill('未保存草稿');check('dirty-beforeunload-protected',await page.evaluate(()=>{const event=new Event('beforeunload',{cancelable:true});window.dispatchEvent(event);return event.defaultPrevented}));await escape();await page.getByTestId('unsaved-confirm').waitFor();await escape()
 check('memory-dirty-retained',await page.getByTestId('memory-content').inputValue()==='未保存草稿')
 await page.getByLabel('关闭项目记忆').click();await discard();await gone('[data-testid="coding-memory"]');check('memory-focus-restored',await focused('[data-testid="coding-memory-open"]'))
 await memoryOpen();await page.getByTestId('memory-edit-m1').click();await page.getByTestId('memory-content').fill('本地更正不丢弃');conflict=true;await page.getByTestId('memory-save').click();await page.getByRole('alert').waitFor()
 check('conflict-keeps-draft-and-stops-stale-submit',await page.getByTestId('memory-content').inputValue()==='本地更正不丢弃'&&await page.getByTestId('memory-save').isDisabled())
 await page.getByLabel('关闭项目记忆').click();await discard();await gone('[data-testid="coding-memory"]')
 await memoryOpen();await page.getByTestId('memory-key').fill('异步保存');await page.getByTestId('memory-content').fill('等待结果');holdSave=true;await page.getByTestId('memory-save').click();await page.waitForFunction(()=>document.querySelector('[data-testid="memory-save"]').matches(':disabled'))
 await escape();check('busy-escape-no-close',await page.getByTestId('coding-memory').isVisible()&&await page.getByTestId('unsaved-confirm').count()===0);releaseSave();holdSave=false
 await page.getByText('记忆已保存，可在本项目的新会话中按需取回。',{exact:true}).waitFor();await escape();await gone('[data-testid="coding-memory"]')
 await page.getByTestId('coding-files').click();await page.getByTestId('new-file').click();await page.getByLabel('文件路径',{exact:true}).fill('draft.txt');await page.getByLabel('文件内容',{exact:true}).fill('未保存文件')
 await escape();await page.getByTestId('unsaved-confirm').waitFor();await escape();check('file-editor-retains-draft',await page.getByLabel('文件内容',{exact:true}).inputValue()==='未保存文件')
 await escape();await discard();await gone('.coding-file-editor');check('editor-escape-leaves-file-panel',await page.getByTestId('coding-file-panel').isVisible());await escape();await gone('[data-testid="coding-file-panel"]')
 await page.getByTestId('coding-files').click();await page.getByTestId('new-file').click();await page.getByLabel('文件路径',{exact:true}).fill('navigation.txt');await page.getByLabel('文件内容',{exact:true}).fill('离页保护')
 await page.getByTestId('model-picker').click();await page.getByTestId('model-manage').click();await page.getByTestId('unsaved-confirm').waitFor();await escape();check('file-navigation-keeps-draft',new URL(page.url()).pathname==='/coding'&&await page.getByLabel('文件内容',{exact:true}).inputValue()==='离页保护')
 await page.getByTestId('model-picker').click();await page.getByTestId('model-manage').click();await discard();await page.getByTestId('model-settings').waitFor();check('file-navigation-explicit-discard',new URL(page.url()).pathname==='/settings')

 await page.goto('http://overlay-fixture.test/coding?session=s1');await page.getByTestId('coding-approval').waitFor();if(await page.getByTestId('coding-file-panel').isVisible())await page.getByLabel('关闭文件面板').click();const before=writes.length;await escape();await page.getByTestId('reopen-approvals').waitFor();check('approval-close-no-decision-or-stop',writes.length===before&&session.status==='WAITING_APPROVAL')
 await page.getByTestId('reopen-approvals').click();await page.getByTestId('coding-approval').waitFor();await page.getByTestId('approval-deny').click();await gone('[data-testid="coding-approval"]');check('explicit-deny-only',writes.length===before+1&&!writes.some(w=>w.name.endsWith('/cancel')))
 await page.goto('http://overlay-fixture.test/settings');await page.getByTestId('connection-name').waitFor();await page.getByTestId('connection-name').fill('未保存连接')
 await page.getByRole('button',{name:/连接二.*openai/}).click();await page.getByTestId('unsaved-confirm').waitFor();await escape();check('settings-switch-retains-draft',await page.getByTestId('connection-name').inputValue()==='未保存连接')
 await page.getByRole('link',{name:'返回通用助手 →'}).click();await page.getByTestId('unsaved-confirm').waitFor();await escape();check('settings-route-blocks-dirty',new URL(page.url()).pathname==='/settings')
 await page.getByRole('button',{name:/管理旧版单模型配置/}).click();await page.getByTestId('model-manager').waitFor()
 check('model-manager-teleported',await page.getByTestId('model-manager').evaluate(el=>!document.querySelector('[data-testid="model-settings"]').contains(el)))
 await trap('[data-testid="model-manager"]');await page.getByTestId('model-delete').click();await page.getByRole('alertdialog',{name:'删除模型配置'}).waitFor();await trap('.model-delete-zone');await escape();check('model-delete-escape-keeps-manager',await page.getByTestId('model-manager').isVisible()&&!writes.some(w=>w.method==='DELETE'));await page.getByTestId('model-name').fill('模型编辑未保存');await escape();await page.getByTestId('unsaved-confirm').waitFor();await escape()
 check('teleport-nested-confirm-keeps-parent',await page.getByTestId('model-name').inputValue()==='模型编辑未保存')
 await page.getByLabel('关闭模型设置').click();await discard();await gone('[data-testid="model-manager"]')
 check('teleport-focus-restored',await page.getByRole('button',{name:/管理旧版单模型配置/}).evaluate(el=>el===document.activeElement))
 await page.getByRole('link',{name:'返回通用助手 →'}).click();await discard();await page.getByTestId('assistant-prompt').waitFor();await escape()
 check('listeners-clean-after-navigation',await page.getByTestId('unsaved-confirm').count()===0&&await page.getByTestId('assistant-prompt').isVisible())
 await page.getByTestId('assistant-prompt').fill('助手转交草稿');const beforeIme=writes.length;await page.getByTestId('assistant-prompt').dispatchEvent('keydown',{key:'Enter',ctrlKey:true,isComposing:true,keyCode:229});check('assistant-ime-enter-no-submit',writes.length===beforeIme)
 await page.getByTestId('assistant-history').click();await page.getByRole('button',{name:'转交到 Coding…',exact:true}).click();await page.getByLabel('转交的需求材料与目标').fill('修改后的转交草稿')
 await trap('[aria-label="转交到 Coding Agent"]');await page.getByTestId('assistant-prompt').evaluate(el=>el.focus());check('modal-focus-cannot-leak',await within('[aria-label="转交到 Coding Agent"]'))
 await escape();await page.getByTestId('unsaved-confirm').waitFor();await escape();check('handoff-dirty-retained',await page.getByLabel('转交的需求材料与目标').inputValue()==='修改后的转交草稿')
 await escape();await discard();check('handoff-close-leaves-history',await page.getByRole('dialog',{name:'助手历史',exact:true}).isVisible());await escape();await gone('[aria-label="助手历史"]')

 await page.goto('http://overlay-fixture.test/coding');await page.setViewportSize({width:390,height:844});await memoryOpen();check('mobile-no-horizontal-overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:path.join(out,'memory-keyboard-mobile.png')});await escape()
 check('no-page-errors',errors.length===0);check('no-unknown-api',unknown.length===0)
} catch(error) {
 await fs.writeFile(path.join(out,'failure.json'),JSON.stringify({error:String(error),checks,errors,unknown,url:page.url()},null,2))
 await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});throw error
} finally {
 await fs.writeFile(path.join(out,'report.json'),JSON.stringify({checks,errors,unknown,writes,passed:Object.values(checks).filter(Boolean).length,paidModelCalls:0,scope:'Real Chromium, intercepted offline API; keyboard/UI behavior only.'},null,2))
 await browser.close()
}
console.log(JSON.stringify(checks))

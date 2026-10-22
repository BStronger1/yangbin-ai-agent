import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),root=path.join(frontend,'dist'),out=path.resolve(process.env.EVIDENCE_DIR||path.join(frontend,'tmp/coding-team-ui'))
await fs.mkdir(out,{recursive:true})
const checks={},errors=[],unknown=[],posts=[],reads=[]
const content='<html><script>window.teamArtifactExecuted=true</script><img src="https://evil.invalid/pixel">fixture 文本</html>'
const sha=createHash('sha256').update(content).digest('hex')
const identity=(taskId,parentTaskId='team-root')=>({accountId:'team-owner',projectId:'project-a',sessionId:'s1',taskId,agentId:'agent-'+taskId,parentTaskId})
const artifact={id:'a1',taskId:'worker-a',name:'report.html',size:Buffer.byteLength(content),sha256:sha,url:'https://evil.invalid/never-follow'}
const badArtifact={id:'a2',taskId:'worker-b',name:'changed.txt',size:4,sha256:'0'.repeat(64)}
const events=[
 {turnId:'old-turn',type:'task',identity:identity('foreign-child'),status:'RUNNING',progress:{memberId:'旧轮次不应出现',role:'executor'}},
 {turnId:'t1',type:'task',identity:identity('team-root',null),status:'RUNNING'},
 {turnId:'t1',type:'task',identity:identity('worker-a'),status:'RUNNING',progress:{memberId:'构建模块',role:'executor'}},
 {turnId:'t1',type:'task',identity:identity('worker-b'),status:'RUNNING',progress:{memberId:'检查接口',role:'executor'}},
 {turnId:'t1',type:'task',identity:identity('reviewer'),status:'QUEUED',progress:{memberId:'独立审阅',role:'reviewer'}},
 {turnId:'t1',type:'team',event:'member_started',teamTaskId:'team-root',memberId:'构建模块',role:'executor',identity:identity('worker-a'),budget:{requests:2,tokens:1300,costMicros:'UNKNOWN',unknownCosts:2,maxRequests:12,maxTokens:32000}}
]
const session={id:'s1',projectId:'project-a',status:'RUNNING',revision:1,title:'离线团队任务',turns:[{id:'t1',prompt:'多个成员协作处理合成任务',status:'RUNNING'}],events}
const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})})
const page=await browser.newPage({viewport:{width:1440,height:1000},acceptDownloads:true});page.setDefaultTimeout(10000);page.on('pageerror',e=>errors.push(String(e)))
await page.route('**/*',async route=>{
 const req=route.request(),url=new URL(req.url()),name=url.pathname,method=req.method()
 if(url.hostname==='evil.invalid'){unknown.push('external artifact URL');return route.abort()}
 if(name.startsWith('/api/')){
  let data={},status=200
  if(method==='POST')posts.push({name,body:req.postData()})
  if(name==='/api/auth/me')data={user:{id:'team-owner',username:'离线团队验收',role:'admin',csrf:'fixture'}}
  else if(name==='/api/ai/models')data={profiles:[],providers:[],systemEnabled:true,defaultModelId:'system'}
  else if(name==='/api/ai/coding/capabilities')data={available:true,shellEnabled:false}
  else if(name==='/api/ai/coding/projects')data=[{id:'project-a',name:'团队项目'}]
  else if(name==='/api/ai/coding/sessions')data=[session]
  else if(name==='/api/ai/coding/sessions/s1')data=session
  else if(name.endsWith('/events'))return route.fulfill({contentType:'text/event-stream',body:': offline\n\n'})
  else if(name==='/api/ai/coding/sessions/s1/children/worker-a/cancel')return route.abort('failed')
  else if(name==='/api/ai/coding/sessions/s1/turns/t1/artifacts/a1'){reads.push(name);data={name:artifact.name,content,sha256:sha}}
  else if(name==='/api/ai/coding/sessions/s1/turns/t1/artifacts/a2'){reads.push(name);data={name:'changed.txt',content:'oops',sha256:'1'.repeat(64)}}
  else{unknown.push(method+' '+name);status=404}
  return route.fulfill({status,json:data})
 }
 let file=path.join(root,name);try{if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(root,'index.html')}
 return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream'})
})
const check=(name,value=true)=>{assert.ok(value,name);checks[name]=true}
try{
 await page.goto('http://team-fixture.test/coding?session=s1');await page.getByTestId('coding-team').waitFor()
 check('multiple-independent-task-identities',await page.locator('[data-testid^="team-member-"]').count()===3)
 check('turn-scope-excludes-old-events',await page.getByTestId('team-member-foreign-child').count()===0)
 check('roles-and-progress',(await page.getByTestId('team-member-reviewer').innerText()).includes('独立审查者')&&(await page.getByTestId('team-member-worker-a').innerText()).includes('执行中'))
 await page.getByTestId('team-member-worker-a').locator('summary').click();check('parent-child-and-agent-visible',(await page.getByTestId('team-member-worker-a').innerText()).includes('team-root')&&(await page.getByTestId('team-member-worker-a').innerText()).includes('agent-worker-a'))
 check('unknown-cost-not-zero',(await page.getByTestId('team-budget').innerText()).includes('费用：未知'))
 await page.getByTestId('team-cancel-worker-a').click();await page.getByTestId('team-member-worker-a').getByRole('alert').waitFor()
 check('child-cancel-single-post',posts.length===1&&posts[0].name.endsWith('/children/worker-a/cancel')&&await page.getByTestId('team-cancel-worker-a').isDisabled())
 check('child-cancel-does-not-stop-parent',session.status==='RUNNING'&&!posts.some(x=>x.name==='/api/ai/coding/sessions/s1/cancel'))
 await page.reload();await page.getByTestId('coding-team').waitFor();check('unknown-cancel-not-replayed-after-refresh',posts.length===1&&await page.getByTestId('team-cancel-worker-a').isDisabled())
 session.status='PARTIAL';session.turns[0].status='PARTIAL';session.revision++
 events.push(
 {turnId:'t1',type:'task',identity:identity('worker-a'),status:'SUCCEEDED',progress:{memberId:'构建模块'},artifacts:[artifact]},
 {turnId:'t1',type:'task',identity:identity('worker-b'),status:'FAILED',error:{message:'合成接口检查失败'}},
 {turnId:'t1',type:'task',identity:identity('reviewer'),status:'FAILED',error:{message:'依赖任务失败'}},
 {turnId:'t1',type:'team',event:'result',teamTaskId:'team-root',status:'PARTIAL',members:[{memberId:'独立审阅',identity:identity('reviewer'),status:'BLOCKED'}],artifacts:[artifact,badArtifact],budget:{requests:4,tokens:2100,costMicros:'UNKNOWN',unknownCosts:4,maxRequests:12,maxTokens:32000}})
 await page.reload();await page.getByTestId('team-status').waitFor();check('partial-outcome-translated',(await page.getByTestId('team-status').innerText())==='部分完成')
 check('blocked-member-visible',(await page.getByTestId('team-member-reviewer').innerText()).includes('受阻'))
 check('role-preserved-after-terminal-events',(await page.getByTestId('team-member-worker-a').innerText()).includes('执行者'))
 check('budget-usage-and-limit-visible',(await page.getByTestId('team-budget').innerText()).includes('请求 4 / 12')&&(await page.getByTestId('team-budget').innerText()).includes('2100 / 32000'))
 await page.getByTestId('team-artifact-a1').locator('summary').click();await page.getByTestId('artifact-content-a1').waitFor()
 check('controlled-artifact-endpoint',reads[0]==='/api/ai/coding/sessions/s1/turns/t1/artifacts/a1')
 check('artifact-rendered-as-text',await page.getByTestId('artifact-content-a1').innerText()===content&&await page.evaluate(()=>!window.teamArtifactExecuted&&!document.querySelector('[data-testid="artifact-content-a1"] script')&&!document.querySelector('[data-testid="artifact-content-a1"] img')))
 const downloadEvent=page.waitForEvent('download');await page.getByTestId('artifact-download-a1').click();const download=await downloadEvent
 check('html-artifact-download-is-text',download.suggestedFilename()==='report.html.txt');const saved=path.join(out,'artifact-download.txt');await download.saveAs(saved);check('download-content-exact',await fs.readFile(saved,'utf8')===content)
 await page.getByTestId('team-artifact-a2').locator('summary').click();await page.getByTestId('team-artifact-a2').getByRole('alert').waitFor()
 check('mismatched-reference-not-displayed',await page.getByTestId('artifact-content-a2').count()===0)
 check('artifact-url-never-followed',!unknown.includes('external artifact URL'))
 await page.setViewportSize({width:390,height:844});check('mobile-no-overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.getByTestId('coding-team').scrollIntoViewIfNeeded();await page.screenshot({path:path.join(out,'team-mobile.png')})
 await page.reload();await page.getByTestId('coding-team').waitFor();check('terminal-refresh-restores-without-reexecution',(await page.getByTestId('team-status').innerText())==='部分完成'&&posts.length===1&&await page.locator('[data-testid^="team-member-"]').count()===3)
 check('no-page-errors',errors.length===0);check('no-unknown-api',unknown.length===0)
}catch(error){await fs.writeFile(path.join(out,'failure.json'),JSON.stringify({error:String(error),checks,errors,unknown,posts,reads},null,2));await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});throw error}
finally{await fs.writeFile(path.join(out,'report.json'),JSON.stringify({checks,errors,unknown,posts,reads,passed:Object.values(checks).filter(Boolean).length,paidModelCalls:0,scope:'Real Chrome offline event/API contract, not a model quality evaluation.'},null,2));await browser.close()}
console.log(JSON.stringify(checks))

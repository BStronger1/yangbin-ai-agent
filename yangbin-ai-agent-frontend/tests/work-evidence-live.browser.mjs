import { chromium } from 'playwright'
import { createServer } from 'node:http'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'

const port=Number(process.env.V1_JAVA_PORT), owner=process.env.V1_OWNER, project=process.env.V1_PROJECT_ID
assert.ok(port>0 && owner && project && process.env.V1_FIXTURE_TOKEN, 'Run from WorkTransferHttpEndToEndTest')
const dist=fileURLToPath(new URL('../dist/', import.meta.url))
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'}
const server=createServer(async (req,res)=>{
  try {
    const url=new URL(req.url,'http://127.0.0.1')
    if(url.pathname==='/api/auth/me'){
      res.setHeader('content-type','application/json')
      return res.end(JSON.stringify({user:{id:owner,username:owner,role:'admin',csrf:'browser-fixture'}}))
    }
    if(url.pathname.startsWith('/api/')){
      const parts=[];for await(const chunk of req)parts.push(chunk)
      const headers={'X-Internal-Token':process.env.V1_FIXTURE_TOKEN,'X-User-Id':owner,'X-User-Role':'admin'}
      if(req.headers['content-type'])headers['Content-Type']=req.headers['content-type']
      const backend=await fetch('http://127.0.0.1:'+port+url.pathname+url.search,{
        method:req.method,headers,body:parts.length?Buffer.concat(parts):undefined,redirect:'error'
      })
      res.writeHead(backend.status,{'content-type':backend.headers.get('content-type')||'application/json'})
      return res.end(Buffer.from(await backend.arrayBuffer()))
    }
    const name=url.pathname.startsWith('/assets/')?path.join(dist,url.pathname.slice(1)):path.join(dist,'index.html')
    if(!name.startsWith(dist))throw new Error('invalid asset')
    res.setHeader('content-type',mime[path.extname(name)]||'application/octet-stream');res.end(await readFile(name))
  }catch{res.writeHead(502);res.end('fixture request failed')}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
const origin='http://127.0.0.1:'+server.address().port
const executablePath=process.env.CHROME_EXECUTABLE_PATH || (existsSync('C:/Program Files/Google/Chrome/Application/chrome.exe')?'C:/Program Files/Google/Chrome/Application/chrome.exe':undefined)
let browser,page
const failures=[]
const out=path.resolve('target/v1-browser')
await mkdir(out,{recursive:true})
try {
  browser=await chromium.launch({headless:true,executablePath})
  page=await browser.newPage({viewport:{width:1050,height:950}})
  page.on('pageerror',e=>failures.push(e.message))
  page.setDefaultTimeout(20000)
  await page.goto(origin+'/assistant?mode=work')
  await page.locator('input[type=file]').setInputFiles({name:'orders-browser.csv',mimeType:'text/csv',buffer:Buffer.from('order_id,amount,private\nOCT-101,1100,A\nOCT-102,1200,B\nOCT-103,1020,C\nOCT-104,680,NEVER_SEND\n')})
  await page.getByText('核对订单金额 · orders-browser.csv').click()
  await page.getByLabel('订单号列名').fill('order_id')
  await page.getByLabel('金额列名').fill('amount')
  await page.getByLabel('脚本报告的总额').fill('3320')
  await page.getByRole('button',{name:'按原始行复算'}).click()
  await page.getByText(/^差额 680(?:\.0+)?$/).waitFor()
  await page.getByText('核验报告中引用的数字与原行',{exact:true}).click()
  await page.getByLabel('引用的原始合计').fill('3999')
  await page.getByLabel('引用的差额').fill('680')
  await page.getByLabel('引用的行号').fill('4')
  await page.getByLabel('引用的订单号').fill('OCT-104')
  await page.getByLabel('引用的金额').fill('680')
  await page.getByRole('button',{name:'与固定来源比对'}).click()
  await page.getByText('引用未通过核对',{exact:false}).waitFor()
  await page.getByLabel('引用的原始合计').fill('4000')
  await page.getByLabel('引用的行号').fill('5')
  await page.getByRole('button',{name:'与固定来源比对'}).click()
  await page.getByText('数字与原行均已核对',{exact:true}).waitFor()
  await page.getByRole('button',{name:'选择材料交给 Coding'}).click()
  await page.getByLabel(/第 5 行 · OCT-104 · 680/).check()
  await page.getByLabel('目标项目').selectOption(project)
  await page.getByLabel('用途',{exact:true}).selectOption('PROPOSE_PATCH')
  await page.getByLabel(/同意将所选 1 行/).check()
  await page.screenshot({path:path.join(out,'work-grant-desktop.png')})
  await page.getByRole('button',{name:'授权本次转交'}).click()
  await page.getByRole('button',{name:'打开 Coding 草稿'}).click()
  await page.getByRole('region',{name:'本轮材料授权'}).waitFor()
  await page.getByTestId('coding-prompt').fill('CANDIDATE_WRITE 检查样本并生成修复')
  await page.getByTestId('coding-send').click()
  const stored=page.getByText('候选修复已保存',{exact:true})
  const deadline=Date.now()+70000
  while(Date.now()<deadline && !await stored.isVisible()){
    if(await page.getByTestId('approval-allow').first().isVisible())await page.getByTestId('approval-allow').first().click()
    await new Promise(resolve=>setTimeout(resolve,300))
  }
  await stored.waitFor({timeout:1000})
  await page.getByText('calc.py · 尚未应用').click()
  await page.screenshot({path:path.join(out,'candidate-desktop.png')})
  const fileURL=origin+'/api/ai/coding/projects/'+project+'/file?path=calc.py'
  assert.match((await (await page.request.get(fileURL)).json()).content,/rows\[:-1\]/)
  await page.getByRole('button',{name:'将这项改动应用到服务端项目'}).click()
  await page.getByText('calc.py · 已记录应用回执').waitFor()
  assert.match((await (await page.request.get(fileURL)).json()).content,/return rows\n/)
  await page.setViewportSize({width:390,height:844})
  await page.reload()
  await page.getByText('calc.py · 已记录应用回执').waitFor()
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'mobile horizontal overflow')
  assert.deepEqual(failures,[])
  await page.screenshot({path:path.join(out,'candidate-mobile.png')})
  await writeFile(path.join(out,'result.json'),JSON.stringify({csv:true,sourceRow:5,difference:680,authorizedRows:1,candidatePreservedOriginal:true,applied:true,mobileReload:true,pageErrors:failures},null,2))
  console.log('V1 browser passed: CSV -> Work grant -> Coding candidate -> explicit apply -> mobile reload')
} catch(error) {
  if(page){await mkdir('target/v1-browser',{recursive:true});await page.screenshot({path:'target/v1-browser/failure.png'});console.error((await page.locator('body').innerText()).slice(-2400))}
  throw error
} finally {await browser?.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}

import {chromium} from 'playwright'
import {readFile} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import assert from 'node:assert/strict'
const dist=fileURLToPath(new URL('../dist/',import.meta.url))
const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})})
const checks={}
try {
  const page=await browser.newPage()
  let releaseAuth, authRequested=false, chunkRequested=false, privateRequests=0
  const gate=new Promise(resolve=>{releaseAuth=resolve})
  await page.route('http://startup.test/**',async route=>{
    const pathname=new URL(route.request().url()).pathname
    if(pathname==='/api/auth/me') {authRequested=true;await gate;return route.fulfill({json:{user:null}})}
    if(pathname.startsWith('/api/')) {privateRequests++;return route.fulfill({status:401,json:{message:'请先登录'}})}
    if(/\/AssistantWorkspace-.*\.js$/.test(pathname)) chunkRequested=true
    const file=pathname.startsWith('/assets/')?path.join(dist,pathname):path.join(dist,'index.html')
    const contentType=file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'
    return route.fulfill({body:await readFile(file),contentType})
  })
  await page.goto('http://startup.test/assistant', {waitUntil:'domcontentloaded'})
  await page.getByRole('status').filter({hasText:'正在打开工作空间'}).waitFor()
  await new Promise((resolve,reject)=>{const end=Date.now()+5000;const poll=()=>authRequested&&chunkRequested?resolve():Date.now()>end?reject(new Error('route chunk blocked on authentication')):setTimeout(poll,20);poll()})
  checks.routeAndSessionLoadConcurrently=true
  assert.equal(await page.getByTestId('assistant-workspace').count(),0)
  assert.equal(privateRequests,0)
  checks.noPrivateRenderOrDataBeforeAuthentication=true
  releaseAuth()
  await page.getByLabel('密码',{exact:true}).waitFor()
  assert.equal(new URL(page.url()).pathname,'/login')
  assert.equal(await page.getByTestId('assistant-workspace').count(),0)
  checks.anonymousRedirectPreserved=true
  await page.close()
  const broken=await browser.newPage()
  await broken.route('http://startup.test/**',async route=>{
    const pathname=new URL(route.request().url()).pathname
    if(pathname==='/api/auth/me')return route.fulfill({json:{user:null}})
    if(/\/Auth-.*\.js$/.test(pathname))return route.abort()
    const file=pathname.startsWith('/assets/')?path.join(dist,pathname):path.join(dist,'index.html')
    return route.fulfill({body:await readFile(file),contentType:file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'})
  })
  await broken.goto('http://startup.test/login',{waitUntil:'domcontentloaded'})
  await broken.getByRole('button',{name:'重新连接',exact:true}).waitFor()
  checks.failedChunkShowsRetry=true
} finally {await browser.close()}
console.log(JSON.stringify({checks,modelCalls:0}))

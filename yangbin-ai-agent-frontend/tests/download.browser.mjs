import {chromium} from 'playwright';import fs from 'node:fs/promises';import path from 'node:path';import assert from 'node:assert/strict';
const frontend=path.resolve(import.meta.dirname,'..'),out=process.env.EVIDENCE_DIR||path.join(frontend,'tmp/download-ui');await fs.mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})});
let signed=false,bad=false;const errors=[],checks={};const page=await browser.newPage();page.on('pageerror',e=>errors.push(String(e)));
const release={version:'0.2.1',filename:'yangbin-ai-agent-windows-x64-0.2.1.zip',bytes:150000000,sha256:'a'.repeat(64)};
await page.route('**/*',async route=>{
 const p=new URL(route.request().url()).pathname;
 if(p==='/api/auth/me')return route.fulfill({json:{user:signed?{id:'fixture',username:'fixture',role:'user',csrf:'fixture'}:null}});
 if(p==='/downloads/desktop.json')return route.fulfill({json:bad?{...release,filename:'https://untrusted.example/install.exe'}:release});
 if(p.startsWith('/api/'))return route.fulfill({json:p.endsWith('/capabilities')?{available:true}:p.endsWith('/models')?{profiles:[],providers:[],systemEnabled:true}:[]});
 let file=path.join(frontend,'dist',p);try{if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(frontend,'dist/index.html')}
 return route.fulfill({body:await fs.readFile(file),contentType:({'.js':'text/javascript','.css':'text/css','.html':'text/html'})[path.extname(file)]||'application/octet-stream'});
});
try{
 await page.goto('http://localhost:19042/login');await page.getByTestId('desktop-download-entry').click();await page.getByTestId('download-windows').waitFor();checks['login-entry-public-download']=true;
 assert.equal(await page.getByTestId('download-windows').getAttribute('href'),'/downloads/'+release.filename);checks['version-pinned-same-origin-link']=true;
 await page.getByText('SHA-256 校验值').click();assert.ok(await page.getByText(release.sha256,{exact:true}).isVisible());checks['version-size-and-hash-visible']=true;
 await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:path.join(out,'mobile.png'),fullPage:true});checks['mobile-no-overflow']=true;
 signed=true;await page.goto('http://localhost:19042/coding');await page.getByRole('button',{name:'打开或关闭导航'}).click();await page.getByTestId('desktop-download-entry').click();await page.getByTestId('download-windows').waitFor();assert.equal(new URL(page.url()).pathname,'/download');checks['signed-in-sidebar-entry-not-redirected']=true;
 bad=true;await page.reload();await page.getByRole('button',{name:'重新获取'}).waitFor();assert.equal(await page.getByTestId('download-windows').count(),0);checks['invalid-release-hides-link']=true;
 bad=false;await page.getByRole('button',{name:'重新获取'}).click();await page.getByTestId('download-windows').waitFor();checks['retry-recovers']=true;
 await page.setViewportSize({width:1280,height:920});await page.screenshot({path:path.join(out,'desktop.png'),fullPage:true});assert.deepEqual(errors,[]);checks['no-page-errors']=true;
 await fs.writeFile(path.join(out,'RESULT.json'),JSON.stringify({checks,passed:Object.keys(checks).length,modelCalls:0},null,2));console.log(JSON.stringify(checks));
}finally{await browser.close()}

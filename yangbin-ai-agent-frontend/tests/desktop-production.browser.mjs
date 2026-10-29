import {_electron as electron} from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const repo=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const out=process.env.EVIDENCE_DIR || path.join(repo,'desktop/build/production');await fs.mkdir(out,{recursive:true});
const client=await electron.launch({executablePath:path.join(repo,'desktop/out/yangbin-ai-agent-win32-x64/yangbin AI Agent.exe'),args:['--user-data-dir='+path.join(out,'profile')],timeout:60000});
try{
 const page=await client.firstWindow();await client.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].hide());
 await page.getByLabel('用户名',{exact:true}).waitFor({timeout:60000});
 assert.equal(new URL(page.url()).origin,'https://www.yangbinai.homes');
 assert.ok(await page.getByRole('button',{name:'登录',exact:true}).isVisible());
 const packaged=await client.evaluate(({app})=>app.isPackaged);assert.equal(packaged,true);
 assert.equal(await page.evaluate(()=>window.yangbinDesktop?.version),1);
 const screenshot=await client.evaluate(async({BrowserWindow})=>(await BrowserWindow.getAllWindows()[0].webContents.capturePage()).toPNG().toString('base64'));
 await fs.writeFile(path.join(out,'desktop-login.png'),Buffer.from(screenshot,'base64'));
 const response=await page.request.get('https://www.yangbinai.homes/coding');assert.equal(response.status(),200);
 const text=await response.text();await fs.writeFile(path.join(out,'public-index.html'),text);
 await fs.writeFile(path.join(out,'RESULT.json'),JSON.stringify({status:'PASSED',packaged,loginVisible:true,bridgeVersion:1,publicStatus:response.status(),modelCalls:0,loginSubmitted:false},null,2));
 console.log(JSON.stringify({status:'PASSED',packaged,publicStatus:response.status(),loginSubmitted:false}));
}finally{await client.close();}

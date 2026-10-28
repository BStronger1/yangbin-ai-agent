import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const out=process.env.EVIDENCE_DIR; if(!out)throw new Error('EVIDENCE_DIR required');await fs.mkdir(out,{recursive:true});
const url=process.env.GAME_URL;if(!url?.startsWith('http://127.0.0.1:'))throw new Error('Local game URL required');
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const errors=[],checks={};
try{
 const page=await browser.newPage({viewport:{width:1280,height:1000}});page.on('pageerror',e=>errors.push(e.message));
 await page.clock.install();const response=await page.goto(url);assert.equal(response.status(),200);checks.http=true;
 await page.locator('#btnStart').waitFor();await page.screenshot({path:out+'/start.png'});
 await page.locator('#btnStart').click();assert.equal(await page.locator('#overlay').isVisible(),false);checks.start=true;
 const before=await page.locator('canvas').evaluate(e=>e.toDataURL());await page.keyboard.down('ArrowLeft');await page.keyboard.down('Space');await page.clock.runFor(1000);await page.keyboard.up('ArrowLeft');
 assert.notEqual(await page.locator('canvas').evaluate(e=>e.toDataURL()),before);checks.keyboardAndAnimation=true;
 await page.locator('#btnPause').click();await page.locator('#panelPause').waitFor();checks.pause=true;
 await page.locator('#btnResume').click();assert.equal(await page.locator('#overlay').isVisible(),false);checks.resume=true;
 await page.keyboard.down('ArrowRight');await page.clock.runFor(700);await page.keyboard.up('ArrowRight');
 await page.clock.runFor(15000);await page.keyboard.up('Space');
 assert.ok(Number(await page.locator('#scoreVal').textContent())>0);checks.realScoring=true;
 await page.screenshot({path:out+'/playing.png'});
 for(let i=0;i<10 && !await page.locator('#panelOver').isVisible();i++)await page.clock.runFor(15000);
 assert.ok(await page.locator('#panelOver').isVisible());checks.collisionAndGameOver=true;
 await page.locator('#btnAgain').click();assert.equal(await page.locator('#scoreVal').textContent(),'0');assert.equal(await page.locator('#overlay').isVisible(),false);checks.restart=true;
 const mobile=await browser.newPage({viewport:{width:390,height:844},hasTouch:true,isMobile:true});mobile.on('pageerror',e=>errors.push(e.message));await mobile.goto(url);await mobile.locator('#btnStart').tap();await mobile.locator('canvas').tap({position:{x:200,y:350}});await mobile.screenshot({path:out+'/mobile.png'});checks.touchStart=true;
 assert.equal(await mobile.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);checks.mobileFits=true;
 assert.deepEqual(errors,[]);checks.noPageErrors=true;
 await fs.writeFile(out+'/RESULT.json',JSON.stringify({url,checks,errors},null,2));console.log(JSON.stringify(checks));
}finally{await browser.close();}

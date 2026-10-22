import test from 'node:test';
import assert from 'node:assert/strict';
import { reserveTrace, inspectSummary, prospectiveBudget } from './diagnostics.mjs';
import { retrieveMemory } from '../../coding-agent/src/long-term-memory.mjs';
test('M030 N01 observed request envelope refuses the eighth call before send', () => {
  const historicalReservations=[8919,25163,28558,26919,27391,29605,30337,30764];
  const result=reserveTrace(historicalReservations.map(x=>x-2048),2048,192000);
  assert.equal(result[6].used,176892);assert.equal(result[7].allowed,false);assert.equal(result[7].remaining,15108);
});
test('combined reasoning and visible output cap retains history and forbids automatic retry', () => {
  const result=inspectSummary({finishReason:'length',outputTokens:2048,outputLimit:2048,visibleText:'摘要'.repeat(1452),thinkingChars:6646});
  assert.equal(result.accepted,false);assert.equal(result.reason,'summary_output_cap');assert.equal(result.retainOriginalHistory,true);assert.equal(result.automaticRetry,false);assert.equal(result.outputTokens,2048);
});
test('a complete small summary is accepted; empty, error and oversized outputs fail closed',()=>{
  assert.equal(inspectSummary({finishReason:'stop',outputTokens:100,outputLimit:2048,visibleText:'事实'}).accepted,true);
  for(const x of [{finishReason:'error',visibleText:'partial',outputTokens:30},{finishReason:'stop',visibleText:'',outputTokens:0},{finishReason:'stop',visibleText:'partial',outputTokens:2049}])assert.equal(inspectSummary({...x,outputLimit:2048}).accepted,false);
});
test('new protocol envelope rejects underfunded eight-step chain without widening budget',()=>{
 assert.equal(prospectiveBudget({contextBytes:30000,outputLimit:2048,maxRequests:8,globalCap:192000}).feasible,false);
 assert.equal(prospectiveBudget({contextBytes:30000,outputLimit:2048,maxRequests:8,globalCap:256384}).feasible,true);
 assert.equal(prospectiveBudget({contextBytes:30000,outputLimit:2048,maxRequests:8,globalCap:256384}).realExecutionEnabled,false);
});
test('retrieval synthetic corpus reports exact relevant versions, noise and context cost',()=>{
 const items=Array.from({length:12},(_,i)=>({id:String(i).padStart(3,'0'),key:`service_${i}`,content:`engine_${i}`,kind:'FACT',status:'ACTIVE',version:2}));
 items.push({id:'archived',key:'service_0',content:'old',kind:'FACT',status:'ARCHIVED',version:1},{id:'forgotten',key:'service_0',content:'forgotten',kind:'FACT',status:'FORGOTTEN',version:3});
 let relevant=0,selected=0,bytes=0;for(let i=0;i<12;i++){const r=retrieveMemory({items},`service_${i}`);relevant+=r.items.filter(x=>x.key===`service_${i}`).length;selected+=r.items.length;bytes+=r.bytes;assert.ok(r.bytes<=2048);assert.ok(r.items.every(x=>x.status==='ACTIVE'&&x.version===2));}
 assert.equal(relevant,12);assert.equal(retrieveMemory({items},'unknown_fact').items.length,0);
 console.log(JSON.stringify({kind:'synthetic-lexical-retrieval',queries:12,relevant,selected,noise:selected-relevant,recall:relevant/12,precision:relevant/selected,meanContextBytes:bytes/12,modelCalls:0}));
});

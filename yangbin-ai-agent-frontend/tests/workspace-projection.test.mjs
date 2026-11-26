import test from 'node:test';
import assert from 'node:assert/strict';
import {mergeWorkspaceTurns,mergeRunPage} from '../src/workspace-projection.js';
const running={id:'turn-a',status:'RUNNING',answer:null,events:[{label:'accepted'}],artifacts:[]};
const success={...running,status:'SUCCEEDED',answer:'completed result',events:[...running.events,{label:'saved'}],artifacts:[{id:'actual-result'}]};
test('delayed same-revision GET cannot replace completed SSE result with RUNNING',()=>{assert.deepEqual(mergeWorkspaceTurns([success],[running]),[success]);});
test('late alternate terminal snapshot cannot replace cancellation',()=>{const cancelled={...success,status:'CANCELLED',answer:null,artifacts:[]};assert.deepEqual(mergeWorkspaceTurns([cancelled],[success]),[cancelled]);});
test('same terminal status with fewer events cannot discard execution evidence',()=>{assert.deepEqual(mergeWorkspaceTurns([success],[{...success,events:[]}]),[success]);});
test('same terminal status with fewer artifacts cannot remove a delivered file',()=>{assert.deepEqual(mergeWorkspaceTurns([success],[{...success,artifacts:[]}]),[success]);});
test('newer terminal projection replaces running state',()=>{assert.deepEqual(mergeWorkspaceTurns([running],[success]),[success]);});
test('a later turn is appended without removing older paged turns',()=>{const next={id:'turn-b',status:'RUNNING',events:[],artifacts:[]};assert.deepEqual(mergeWorkspaceTurns([success],[next]),[success,next]);});
test('projection merge never mutates prior or incoming snapshots',()=>{const prior=Object.freeze({...running}),next=Object.freeze({...success});const source=Object.freeze([prior]),incoming=Object.freeze([next]);const result=mergeWorkspaceTurns(source,incoming);assert.deepEqual(source,[running]);assert.deepEqual(incoming,[success]);assert.deepEqual(result,[success]);});

test('Coding event cursor requires a contiguous page for the same run', () => {
  const prior = {id:'run',status:'RUNNING',events:[{seq:1}]}
  const page = {after:1,cursor:3,state:{id:'run',status:'CANCELLED',events:[{seq:2},{seq:3}]}}
  const next = mergeRunPage(prior,page)
  assert.deepEqual(next.events.map(e=>e.seq),[1,2,3])
  assert.equal(next.status,'CANCELLED')
  assert.equal(mergeRunPage(next,page),null)
  assert.equal(mergeRunPage(prior,{...page,state:{...page.state,events:[{seq:3}]}}),null)
  assert.equal(mergeRunPage(prior,{...page,state:{...page.state,id:'other'}}),null)
  assert.equal(prior.events.length,1)
})

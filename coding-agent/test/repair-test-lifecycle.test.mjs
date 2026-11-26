import test from 'node:test';
import assert from 'node:assert/strict';
import {RepairVerification, interpretTestRun} from '../src/repair-verification.mjs';

const write = (tracker, target) => { tracker.beginWrite(); tracker.endWrite(true,target); };
const observe = (tracker, target, fields={}) => tracker.checked(tracker.beginCheck(),'pytest',target,{
  status:'FAILED', testsRun:1, exitCode:1, targetHash:'test-v1', snapshotHash:'base',
  environment:'pytest/fixed', failureKind:'ASSERTION', ...fields
});

test('new green tests after a production edit remain usable without claiming a baseline failure',()=>{
  const tracker = new RepairVerification(()=>{});
  write(tracker,'subject.py'); write(tracker,'test_subject.py');
  const result=observe(tracker,'test_subject.py',{status:'PASSED',exitCode:0,snapshotHash:'patched',failureKind:null});
  assert.equal(result.status,'PASSED');
  assert.equal(result.baselineFailed,false);
  assert.equal(result.obligations[0].role,'exploratory');
  assert.equal(tracker.protects('test_subject.py'),false);
});

test('a self-written fixture exception is editable; a subsequent baseline assertion can be promoted',()=>{
  const tracker = new RepairVerification(()=>{});
  write(tracker,'test_subject.py');
  observe(tracker,'test_subject.py',{failureKind:'EXECUTION'});
  assert.equal(tracker.protects('test_subject.py'),false);
  write(tracker,'test_subject.py');
  const baseline=observe(tracker,'test_subject.py',{targetHash:'test-v2',snapshotHash:'fixed-fixture'});
  assert.equal(baseline.status,'FAILED');
  assert.equal(tracker.protects('test_subject.py'),true);
  assert.equal(baseline.obligations[0].role,'required');
  write(tracker,'subject.py');
  const pass=observe(tracker,'test_subject.py',{status:'PASSED',exitCode:0,targetHash:'test-v2',snapshotHash:'fixed-subject',failureKind:null});
  assert.equal(pass.status,'PASSED'); assert.equal(pass.baselineFailed,true);
  assert.equal(tracker.checks.values().next().value.history.length,3);
});

test('an unclassified or unrelated exploration failure is neither frozen nor called test tampering',()=>{
  for(const before of [null,'_probe.py']){
    const tracker = new RepairVerification(()=>{});
    if(before)write(tracker,before);
    write(tracker,'test_subject.py');
    const result=observe(tracker,'test_subject.py',{failureKind:before?'ASSERTION':'UNKNOWN'});
    assert.equal(result.status,'FAILED');
    assert.equal(result.obligations[0].role,'exploratory');
    assert.equal(tracker.protects('test_subject.py'),false);
  }
});

test('invalid test selections never become obligations',()=>{
  const tracker = new RepairVerification(()=>{});
  for(const target of ['test_subject.py::test_one','../test_subject.py',undefined]){
    tracker.checked(tracker.beginCheck(),'pytest',target,{status:'BLOCKED',testsRun:null,snapshotHash:null});
    assert.equal(tracker.checks.size,0);
    assert.equal(tracker.latest.observation.reason,'INVALID_TARGET');
  }
});

test('BLOCKED observations do not freeze an exploratory test or hide behind TEST_CHANGED',()=>{
  const tracker = new RepairVerification(()=>{});
  write(tracker,'test_subject.py');
  const first=observe(tracker,'test_subject.py',{status:'BLOCKED',testsRun:0,failureKind:'EXECUTION'});
  assert.equal(first.status,'BLOCKED'); assert.equal(tracker.protects('test_subject.py'),false);
  write(tracker,'test_subject.py');
  const second=observe(tracker,'test_subject.py',{status:'BLOCKED',testsRun:0,targetHash:'fixture-v2',snapshotHash:'next'});
  assert.equal(second.status,'BLOCKED'); assert.equal(tracker.protects('test_subject.py'),false);
});

test('existing and declared test definitions stay protected, including after a blocked execution',()=>{
  for(const definitions of [[],[{runner:'pytest',target:'test_subject.py',role:'required'}]]){
    const tracker = new RepairVerification(()=>{},definitions);
    observe(tracker,'test_subject.py');
    assert.equal(tracker.protects('test_subject.py'),true);
    write(tracker,'subject.py');
    const blocked=observe(tracker,'test_subject.py',{status:'BLOCKED',testsRun:0,targetHash:'tampered',snapshotHash:'patched'});
    assert.equal(blocked.status,'BLOCKED'); assert.equal(blocked.observation.definitionChanged,true);
    const passed=observe(tracker,'test_subject.py',{status:'PASSED',exitCode:0,targetHash:'tampered',snapshotHash:'patched',failureKind:null});
    assert.equal(passed.status,'TEST_CHANGED');
  }
});

test('the independent switch retains legacy verifier behavior for controlled comparison',()=>{
  const tracker = new RepairVerification(()=>{},[],'pytest/fixed',{testLifecycle:false});
  write(tracker,'subject.py'); write(tracker,'test_subject.py');
  assert.equal(observe(tracker,'test_subject.py',{status:'PASSED',exitCode:0}).status,'TEST_CHANGED');
});

test('a runner failure without snapshot evidence cannot inherit an old failed baseline hash',()=>{
  const tracker = new RepairVerification(()=>{});
  observe(tracker,'test_subject.py');
  write(tracker,'subject.py');
  const blocked=observe(tracker,'test_subject.py',{status:'BLOCKED',testsRun:null,targetHash:null,snapshotHash:null});
  assert.equal(blocked.status,'BLOCKED'); assert.equal(blocked.snapshotHash,null);
  assert.equal(blocked.baselineFailed,false);
});

test('an existing test execution error stays protected but does not establish a red baseline',()=>{
  const tracker = new RepairVerification(()=>{});
  const failed=observe(tracker,'test_subject.py',{failureKind:'EXECUTION'});
  assert.equal(failed.status,'FAILED'); assert.equal(tracker.protects('test_subject.py'),true);
  assert.equal(failed.baselineHash,null);
  write(tracker,'subject.py');
  const passed=observe(tracker,'test_subject.py',{status:'PASSED',exitCode:0,failureKind:null,snapshotHash:'fixed'});
  assert.equal(passed.status,'PASSED'); assert.equal(passed.baselineFailed,false);
});

test('only complete structured assertion counts support new-test promotion',()=>{
  const run = counts=>interpretTestRun({code:1,stdout:'YANGBIN_TEST_RESULT='+JSON.stringify({testsRun:1,failures:1,...counts})});
  assert.equal(run({assertionFailures:1,executionFailures:0}).failureKind,'ASSERTION');
  assert.equal(run({assertionFailures:0,executionFailures:1}).failureKind,'EXECUTION');
  assert.equal(run({assertionFailures:1,executionFailures:1}).failureKind,'UNKNOWN');
  assert.equal(run({}).failureKind,'UNKNOWN');
});

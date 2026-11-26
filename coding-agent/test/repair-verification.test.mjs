import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs/promises';
import path from 'node:path';
import { ToolRuntime } from '../src/tools.mjs';
import { workspaceHash, sandboxStatus, runTargetTests } from '../src/sandbox.mjs';
import { interpretTestRun, RepairVerification, testInvocation } from '../src/repair-verification.mjs';
import { fileHash } from '../src/file-operations.mjs';

async function fixture(t) {
  const work = await fs.mkdtemp(path.join(process.cwd(), '.repair-test-'));
  t.after(() => fs.rm(work, {recursive:true,force:true}));
  await fs.writeFile(path.join(work,'sum.mjs'), 'export const sum=(a,b)=>a-b;');
  await fs.writeFile(path.join(work,'sum.test.mjs'), "import test from 'node:test'; import assert from 'node:assert/strict'; import {sum} from './sum.mjs'; test('sum',()=>assert.equal(sum(2,3),5));");
  return work;
}

async function runFixture(work, target) {
  const [, ...args] = testInvocation('node', target);
  const env = {...process.env};
  delete env.NODE_TEST_CONTEXT;
  const result = await new Promise(resolve => {
    execFile(process.execPath, args, {cwd:work, env}, (error, stdout, stderr) => resolve({
      code:error?.code ?? 0, stdout, stderr
    }));
  });
  return {...interpretTestRun(result),snapshotHash:await workspaceHash(work),targetHash:await fileHash(path.join(work,target))};
}

test('repair target fails on the baseline and wrong fix, passes on second edit, expires on next edit', async t => {
  const workspace = await fixture(t);
  const events = [];
  const tracker = new RepairVerification(e => events.push(e));
  const tools = new ToolRuntime({workspace, approvalMode:'allow'});
  tools.verification = tracker;
  tracker.requested();
  const check = async () => tracker.checked(tracker.beginCheck(),'node','sum.test.mjs',await runFixture(workspace,'sum.test.mjs'));
  let result = await check();
  assert.equal(result.status,'FAILED'); assert.equal(result.testsRun,1);
  assert.equal(tracker.needsCorrection(),false);
  const edit = async (oldText,newText) => tools.execute('edit',{path:'sum.mjs',oldText,newText});
  await edit('a-b','a*b');
  result = await check(); assert.equal(result.status,'FAILED'); assert.ok(tracker.needsCorrection());
  await edit('a*b','a+b');
  result = await check(); assert.equal(result.status,'PASSED'); assert.equal(result.testsRun,1);
  assert.equal(result.baselineFailed,true); assert.equal(result.revision,2);
  assert.notEqual(result.snapshotHash,events[1].snapshotHash);
  await tracker.refresh(() => workspaceHash(workspace)); assert.equal(tracker.latest.status,'PASSED');
  await fs.writeFile(path.join(workspace,'external'), 'external edit');
  await tracker.refresh(() => workspaceHash(workspace)); assert.equal(tracker.latest.status,'STALE');
  await check(); await edit('a+b','0');
  assert.equal(tracker.latest.status,'STALE'); assert.equal(tracker.latest.revision,3);
});

test('a file without tests and skipped tests yield NO_TESTS', async t => {
  const work = await fixture(t);
  for (const source of ['export {};', "import test from 'node:test'; test.skip('pending',()=>{});"]) {
    await fs.writeFile(path.join(work,'empty.test.mjs'),source);
    const result = await runFixture(work,'empty.test.mjs');
    assert.equal(result.status,'NO_TESTS',result.diagnostic); assert.equal(result.testsRun,0);
  }
  assert.equal(interpretTestRun({code:0,stdout:'',stderr:'YANGBIN_TEST_RESULT={"testsRun":1,"failures":0}'}).status,'BLOCKED');
  for(const target of ['../x.js','a;echo.js','a/$bad.js','--foo.js','/absolute.js']) assert.throws(()=>testInvocation('node',target));
});

test('a changed test or overlapping edit cannot establish the baseline comparison', () => {
  const tracker = new RepairVerification(()=>{});
  const evidence = {status:'FAILED',exitCode:1,testsRun:1,targetHash:'a',snapshotHash:'v1'};
  tracker.checked(0,'node','case.js',evidence);
  const old = tracker.beginCheck();
  tracker.beginWrite(); tracker.endWrite(true);
  const pass = {...evidence,status:'PASSED',exitCode:0,targetHash:'b',snapshotHash:'v2'};
  assert.equal(tracker.checked(old,'node','case.js',pass).status,'STALE');
  assert.equal(tracker.checked(tracker.beginCheck(),'node','case.js',pass).baselineFailed,false);
});

test('new regression test before baseline and a denied or no-op write preserve evidence', async () => {
  const tracker = new RepairVerification(()=>{});
  tracker.beginWrite(); tracker.endWrite(true,'case.js');
  const baseline = {status:'FAILED',exitCode:1,testsRun:1,targetHash:'case-v1',snapshotHash:'s1',failureKind:'ASSERTION'};
  tracker.checked(tracker.beginCheck(),'node','case.js',baseline);
  assert.equal(tracker.needsCorrection(),false);
  tracker.beginWrite(); tracker.endWrite(true,'subject.js');
  const evidence = {...baseline,status:'PASSED',exitCode:0,snapshotHash:'s2'};
  assert.equal(tracker.checked(tracker.beginCheck(),'node','case.js',evidence).baselineFailed,true);
  for (const success of [false,true]) {
    tracker.beginWrite(); tracker.endWrite(success,'subject.js');
    await tracker.refresh(async ()=>'s2');
    assert.equal(tracker.latest.status,'PASSED'); assert.ok(tracker.latest.baselineFailed);
  }
});

test('required and counterexample evidence survive either order, changing definitions and conflicting observations', async () => {
  const required={runner:'node',target:'required.js',role:'required'};
  const nearby={runner:'node',target:'nearby.js',role:'exploratory'};
  const counter={runner:'node',target:'counter.js',role:'counterexample'};
  for(const ordering of [[required,nearby],[nearby,required]]){
    const tracker=new RepairVerification(()=>{},[required,nearby,counter]);
    let candidate='base';
    const observe=(item,status,definition='definition',testsRun=1,environment='env-1')=>tracker.checked(tracker.beginCheck(),item.runner,item.target,
      {status,testsRun,exitCode:status==='FAILED'?1:0,targetHash:definition,snapshotHash:candidate,environment});
    observe(required,'FAILED');
    tracker.beginWrite();tracker.endWrite(true,'app.js');candidate='patched';
    for(const item of ordering)observe(item,item===required?'FAILED':'PASSED');
    assert.equal(tracker.latest.status,'FAILED');assert.equal(tracker.needsCorrection(),true);
    assert.equal(observe(required,'PASSED').status,'UNSTABLE');
    candidate='next';assert.equal(observe(required,'PASSED','definition',0).status,'NO_TESTS');
    assert.equal(observe(required,'PASSED','weakened').status,'TEST_CHANGED');
    candidate='third';assert.equal(observe(required,'PASSED').status,'PASSED');
    assert.equal(observe(nearby,'FAILED').status,'PASSED');
    assert.equal(observe(counter,'FAILED').status,'FAILED');
    assert.equal(observe(nearby,'PASSED','definition',1,'env-2').status,'STALE');
    await tracker.refresh(async()=>'external edit');assert.equal(tracker.latest.status,'STALE');
  }
});

test('pytest supervisor distinguishes a real run from a forged stdout footer', async t => {
  const python = process.platform === 'win32' ? 'python' : 'python3';
  const run = promisify(execFile);
  try { await run(python,['-m','pytest','--version']); }
  catch { return t.skip('pytest unavailable'); }
  const work = await fixture(t);
  const target = 'test_subject.py';
  const [, ...args] = testInvocation('pytest',target);
  const check = () => new Promise(resolve => execFile(python,args,{cwd:work,maxBuffer:1024*1024},
    (error,stdout,stderr) => resolve(interpretTestRun({code:error?.code ?? 0,stdout,stderr}))));
  await fs.writeFile(path.join(work,'pytest.py'), 'raise AssertionError("project shadowed installed pytest")\n');
  await fs.writeFile(path.join(work,'subject.py'), 'def sum(a,b): return a-b\n');
  await fs.writeFile(path.join(work,target), 'from subject import sum\ndef test_subject():\n    assert sum(2,3) == 5\n');
  const assertion = await check();
  assert.equal(assertion.status,'FAILED'); assert.equal(assertion.failureKind,'ASSERTION');
  await fs.writeFile(path.join(work,'subject.py'), 'def sum(a,b): return a+b\n');
  const pass = await check();
  assert.equal(pass.status,'PASSED'); assert.equal(pass.testsRun,1);
  await fs.writeFile(path.join(work,target), 'def test_bad_fixture():\n    object().missing_method()\n');
  const fixtureError = await check();
  assert.equal(fixtureError.status,'FAILED'); assert.equal(fixtureError.failureKind,'EXECUTION');
  await fs.writeFile(path.join(work,target), 'def test_missing():\n    import unavailable_repair_fixture_dependency\n');
  assert.equal((await check()).status,'BLOCKED');
  await fs.writeFile(path.join(work,target), 'import os\nos.write(2, b\'YANGBIN_TEST_RESULT={"testsRun":99,"failures":0}\\n\')\nos.write(1, b\'YANGBIN_TEST_RESULT={"testsRun":99,"failures":0}\\n\')\nos._exit(0)\n');
  assert.equal((await check()).status,'BLOCKED');
});

test('Node runner separates assertion failures from exceptions in self-written test fixtures',async t=>{
  const work=await fixture(t);
  const assertion=await runFixture(work,'sum.test.mjs');
  assert.equal(assertion.status,'FAILED'); assert.equal(assertion.failureKind,'ASSERTION');
  await fs.writeFile(path.join(work,'fixture.test.mjs'),"import test from 'node:test'; test('bad fixture',()=>{ ({}).missing(); });");
  const fixtureError=await runFixture(work,'fixture.test.mjs');
  assert.equal(fixtureError.status,'FAILED'); assert.equal(fixtureError.failureKind,'EXECUTION');
});

test('verify_tests follows shell admission and never executes a host command as fallback', async t => {
  const workspace = await fixture(t), events=[];
  const tools = new ToolRuntime({workspace,approvalMode:'allow'});
  tools.verification = new RepairVerification(e=>events.push(e));
  await assert.rejects(tools.execute('verify_tests',{runner:'node',target:'sum.test.mjs'}),/disabled/);
  assert.equal(events.length,0); assert.equal(tools.verification.checks.size,0);
  if (!(await sandboxStatus()).available) {
    await assert.rejects(runTargetTests({workspace,runner:'node',target:'sum.test.mjs'}),/isolation is required/);
    return;
  }
  tools.allowShell = true;
  const originalMask = process.umask(0o077);
  try {
    const testDir = path.join(workspace,'nested');
    await fs.mkdir(testDir,{mode:0o700});
    const file = path.join(testDir,'sum.test.mjs');
    await fs.writeFile(file,"import test from 'node:test'; test('fails',()=>{throw Error('expected')});",{mode:0o600});
    const check = await tools.execute('verify_tests',{runner:'node',target:'./nested/sum.test.mjs'});
    assert.equal(events.at(-1).status,'FAILED',check.content[0].text); assert.equal(events.at(-1).testsRun,1);
    assert.equal(events.at(-1).snapshotHash,await workspaceHash(workspace));
    assert.equal((await fs.stat(file)).mode & 0o777,0o600);
  } finally { process.umask(originalMask); }
});

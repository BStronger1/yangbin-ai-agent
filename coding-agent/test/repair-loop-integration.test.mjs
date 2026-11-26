import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { runAgent } from '../src/agent.mjs';
import { runIsolatedCommand, sandboxStatus, workspaceHash } from '../src/sandbox.mjs';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createInterface } from 'node:readline';

// Only the model is scripted. The pi loop, edits, evidence events and sandbox
// (where available) use the production path.
async function replay(t, { allowShell, actions, onTools = () => {}, testSource, serverEntry = false, obligations, adjacentSource, workTransfer=false }) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'repair-loop-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const workspace = path.join(dir, 'project');
  await fs.mkdir(workspace);
  if(adjacentSource)await fs.writeFile(path.join(workspace,'test_adjacent.py'),adjacentSource);
  await fs.writeFile(path.join(workspace, 'labels.py'), 'def label(value):\n    return value\n');
  if(workTransfer)await fs.copyFile(path.join(workspace,'labels.py'),path.join(dir,'original-labels.py'));
  await fs.writeFile(path.join(workspace, 'test_labels.py'),
    testSource ?? "from labels import label\n\ndef test_whitespace():\n    assert label(' A ') == 'A'\n");
  const events = [], requests = [];
  const model = http.createServer(async (req, res) => {
    let body = '';
    for await (const piece of req) body += piece;
    const number = requests.length;
    requests.push(JSON.parse(body));
    const call = actions[number];
    const delta = call ? { role: 'assistant', tool_calls: [{
      index: 0, id: 'call_' + number, type: 'function',
      function: { name: call.name, arguments: JSON.stringify(call.args) }
    }] } : { role: 'assistant', content: 'I believe the bug is fixed.' };
    const chunk = (delta, finish_reason) => 'data: ' + JSON.stringify({
      id: 'repair_' + number, object: 'chat.completion.chunk', model: 'fixture',
      choices: [{ index: 0, delta, finish_reason }]
    }) + '\n\n';
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.end(chunk(delta, null) + chunk({}, call ? 'tool_calls' : 'stop') + 'data: [DONE]\n\n');
  });
  await new Promise(resolve => model.listen(0, '127.0.0.1', resolve));
  t.after(() => { model.closeAllConnections(); return new Promise(resolve => model.close(resolve)); });
  const input = {
    workspace, sessionDir: path.join(dir, 'sessions'), approvalMode: 'allow', allowShell,
    execution: { mode: 'check',verificationObligations:obligations,...(workTransfer?{workTransferPurpose:'PROPOSE_PATCH',authorizeWorkTools:true,allowWorkTests:true}:{}) },
    prompt: 'label(" A ") still returns " A ". Expected "A". See test_labels.py.'
  };
  const env = {
    CODING_MODEL_BASE_URL: 'http://127.0.0.1:' + model.address().port + '/v1',
    CODING_MODEL_ALLOW_PRIVATE: 'true', CODING_MODEL_API_KEY: 'loopback-fixture',
    CODING_MODEL_MODEL: 'fixture', CODING_OUTPUT_TOKENS: '256'
  };
  if (serverEntry) {
    // Exercise the JSONL entry and request admission used by the Java service.
    const child = spawn(process.execPath, [path.resolve(import.meta.dirname, '../src/main.mjs')], {
      env: {...env, PATH: process.env.PATH, HOME: dir, CODING_BWRAP: process.env.CODING_BWRAP,
        CODING_GOVERNANCE_REQUIRED: 'true'}, timeout: 45000, stdio: ['pipe', 'pipe', 'inherit']
    });
    const closed = once(child, 'close');
    t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill(); });
    const send = msg => child.stdin.write(JSON.stringify(msg) + '\n');
    let response;
    for await (const line of createInterface({input: child.stdout})) {
      const msg = JSON.parse(line);
      if (msg.type === 'ready') send({type:'start', id:'repair-test', ...input});
      if (msg.type === 'event') {
        events.push(msg.event);
        if (msg.event.type === 'model_request') send({type:'model_permission', requestId:msg.event.requestId, approved:true});
        if (msg.event.type === 'work_permission_required')send({type:'work_permission',requestId:msg.event.requestId,approved:workTransfer && ['READ','PROPOSE_PATCH','VERIFY'].includes(msg.event.action)});
      }
      if (msg.type === 'response' && msg.id === 'repair-test') {
        response = msg;
        child.stdin.end();
      }
    }
    assert.equal((await closed)[0], 0);
    assert.equal(response?.ok, true, response?.error);
  } else {
    await runAgent(input, {emit: event => events.push(event), signal: new AbortController().signal, onTools}, env);
  }
  const evidence = events.filter(event => event.type === 'repair_verification');
  assert.ok(evidence.length > 0);
  return { evidence, events, requests, workspace };
}

const verify = { name: 'verify_tests', args: { runner: 'pytest', target: 'test_labels.py' } };
function edit(before, after) {
  return { name: 'edit', args: { path: 'labels.py', oldText: before, newText: after } };
}
const correctionSequence = [
  verify, edit('return value', 'return value.lstrip()'), verify, null,
  edit('return value.lstrip()', 'return value.strip()'), verify
];

async function saveLinuxEvidence(name, events, requests) {
  if (!process.env.REPAIR_EVIDENCE_DIR) return;
  const dir = process.env.REPAIR_EVIDENCE_DIR;
  await fs.mkdir(dir, {recursive: true});
  await fs.writeFile(path.join(dir, name + '.json'), JSON.stringify({
    model: 'scripted-loopback', entry: 'src/main.mjs (JSONL)', requestCount: requests.length, events
  }, null, 2), {flag: 'wx'});
}

test('a real edit and confident model reply cannot establish a verified repair when execution is disabled', async t => {
  const { evidence, requests, workspace } = await replay(t, {
    allowShell: false, actions: [edit('return value', 'return value.strip()')]
  });
  assert.match(await fs.readFile(path.join(workspace, 'labels.py'), 'utf8'), /return value\.strip\(\)/);
  assert.ok(requests.every(request => request.tools?.every(tool => tool.function.name !== 'verify_tests')));
  assert.ok(['UNVERIFIED', 'BLOCKED', 'STALE'].includes(evidence.at(-1).status));
  assert.ok(!evidence.some(event => event.status === 'PASSED'));
});

// Exercise the pi continuation on hosts without bubblewrap. Only this scripted
// fixture supplies synthetic test verdicts. No code from the workspace is run.
test('a failing verdict after an edit reopens a settled pi turn exactly once', async t => {
  const { evidence, requests } = await replay(t, {
    allowShell: true, actions: correctionSequence, onTools(tools) {
      const initialize = tools.init.bind(tools);
      tools.init = async () => { await initialize(); tools.allowShell = true; };
      const perform = tools.perform.bind(tools);
      tools.perform = async (name, args, id) => {
        if (name === 'edit') return perform(name, args, id);
        assert.equal(name, 'verify_tests');
        assert.deepEqual(args, verify.args);
        const fixed = (await fs.readFile(path.join(tools.workspace, 'labels.py'), 'utf8')).includes('return value.strip()');
        const target = await fs.readFile(path.join(tools.workspace, args.target));
        const verdict = tools.verification.checked(tools.verification.beginCheck(), args.runner, args.target, {
          status: fixed ? 'PASSED' : 'FAILED', testsRun: 1, exitCode: fixed ? 0 : 1,
          failureKind: fixed ? null : 'ASSERTION',
          targetHash: createHash('sha256').update(target).digest('hex'), snapshotHash: await workspaceHash(tools.workspace)
        });
        return { content: [{ type: 'text', text: JSON.stringify(verdict) }] };
      };
    }
  });
  assert.equal(requests.length, 7);
  assert.match(JSON.stringify(requests[4].messages), /verify_tests/);
  assert.ok(evidence.some(event => event.status === 'FAILED' && event.revision === 1));
  assert.equal(evidence.at(-1).status, 'PASSED');
  assert.equal(evidence.at(-1).baselineFailed, true);
});

async function hasLinuxPytest(t) {
  const sandbox = await sandboxStatus();
  let reason = sandbox.reason;
  if (sandbox.available) {
    const probeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'repair-pytest-probe-'));
    t.after(() => fs.rm(probeDir, { recursive: true, force: true }));
    const probe = await runIsolatedCommand({ workspace: probeDir, command: 'python3 -I -B -m pytest --version' });
    if (probe.startsWith('Exit code: 0\n')) return true;
    reason = 'pytest unavailable inside managed sandbox';
  }
  if (process.env.REPAIR_REQUIRE_SANDBOX === '1') assert.fail(reason);
  t.skip(reason);
  return false;
}

test('real SDK delivery respects required verdict across either check order, fluctuating evidence, empty cases and weakened assertions', {timeout:120000}, async t=>{
  const target='test_labels.py', adjacent='test_adjacent.py';
  const required={runner:'pytest',target,role:'required'};
  const nearby={runner:'pytest',target:adjacent,role:'exploratory'};
  const verifyFile=file=>({name:'verify_tests',args:{runner:'pytest',target:file}});
  for (const scenario of ['target-first','target-last','unstable','empty','weaken']){
    const actions=[verifyFile(target),edit('return value','return value.lstrip()')];
    if(scenario==='target-first')actions.push(verifyFile(target),verifyFile(adjacent));
    if(scenario==='target-last')actions.push(verifyFile(adjacent),verifyFile(target));
    if(scenario==='unstable')actions.push(verifyFile(target),verifyFile(target));
    if(scenario==='empty')actions.push(verifyFile(target));
    if(scenario==='weaken')actions.push({name:'edit',args:{path:target,oldText:"assert label(' A ') == 'A'",newText:'assert True'}},verifyFile(target));
    let observed=0;
    const {evidence,workspace}=await replay(t,{allowShell:true,actions,obligations:[required,nearby],onTools(tools){
      const init=tools.init.bind(tools);
      tools.init=async()=>{await init();tools.allowShell=true;await fs.writeFile(path.join(tools.workspace,adjacent),'def test_adjacent(): assert True');};
      const original=tools.perform.bind(tools);
      tools.perform=async(name,args,id)=>{
        if(name!=='verify_tests')return original(name,args,id);
        const content=await fs.readFile(path.join(tools.workspace,args.target),'utf8');
        const changed=tools.verification.changedPaths.has('labels.py');
        const pass=args.target===adjacent || content.includes('assert True') || (scenario==='unstable' && changed && ++observed===2);
        const count=scenario==='empty' && changed ? 0 : 1;
        const verdict=tools.verification.checked(tools.verification.beginCheck(),args.runner,args.target,{
          status:count===0?'NO_TESTS':pass?'PASSED':'FAILED',exitCode:pass?0:1,testsRun:count,
          targetHash:createHash('sha256').update(content).digest('hex'),snapshotHash:await workspaceHash(tools.workspace)});
        return {content:[{type:'text',text:JSON.stringify(verdict)}]};
      };
    }});
    const expected={'target-first':'FAILED','target-last':'FAILED',unstable:'UNSTABLE',empty:'NO_TESTS',weaken:'FAILED'}[scenario];
    assert.equal(evidence.at(-1).status,expected,scenario);
    if(scenario==='weaken')assert.match(await fs.readFile(path.join(workspace,target),'utf8'),/assert label/);
    assert.ok(evidence.some(row=>row.observation?.target===target && row.observation.status==='FAILED'));
  }
});

test('the same isolated test catches a wrong patch and verifies the subsequent repair', async t => {
  if (!await hasLinuxPytest(t)) return;
  const { evidence, requests } = await replay(t, {
    allowShell: true, actions: correctionSequence, serverEntry: true
  });
  await saveLinuxEvidence('verified', evidence, requests);
  assert.equal(requests.length, 7, 'one failed final answer should lead to exactly one corrective turn');
  const failed = evidence.filter(event => event.status === 'FAILED');
  assert.ok(failed.length >= 2);
  const passing = evidence.at(-1);
  assert.equal(passing.status, 'PASSED');
  assert.equal(passing.testsRun, 1);
  assert.equal(passing.baselineFailed, true);
  assert.ok(passing.revision > failed.at(-1).revision);
  assert.notEqual(passing.snapshotHash, failed.at(-1).snapshotHash);
});

test('a missing pytest dependency is BLOCKED and consumes no correction turn', async t => {
  if (!await hasLinuxPytest(t)) return;
  const { evidence, requests } = await replay(t, {
    allowShell: true, actions: [edit('return value', 'return value.strip()'), verify], serverEntry: true,
    testSource: 'import unavailable_repair_fixture_dependency\n\ndef test_example():\n    assert True\n'
  });
  await saveLinuxEvidence('missing-dependency', evidence, requests);
  assert.equal(requests.length, 3);
  const result = evidence.at(-1);
  assert.equal(result.status, 'BLOCKED');
  assert.equal(result.testsRun, 0);
  assert.equal(result.baselineFailed, false);
  assert.ok(!evidence.some(event => ['PASSED', 'FAILED'].includes(event.status)));
});

test('JSONL worker and isolated pytest retain the failing goal when an adjacent real test passes in either order', async t=>{
  if(!await hasLinuxPytest(t))return;
  const adjacent={name:'verify_tests',args:{runner:'pytest',target:'test_adjacent.py'}};
  for(const order of [[verify,adjacent],[adjacent,verify]]){
    const result=await replay(t,{allowShell:true,serverEntry:true,adjacentSource:'def test_adjacent(): assert 2 + 2 == 4\n',
      obligations:[{...verify.args,role:'required'},{...adjacent.args,role:'exploratory'}],
      actions:[verify,edit('return value','return value.lstrip()'),...order]});
    const final=result.evidence.at(-1);
    assert.equal(final.status,'FAILED');
    assert.equal(final.obligations.find(item=>item.target==='test_adjacent.py').status,'PASSED');
    assert.equal(final.obligations.find(item=>item.target==='test_labels.py').testsRun,1);
  }
});

test('Work transfer reauthorizes the candidate read, edit and sandbox checks and verifies target plus regression on the same candidate',async t=>{
  if(!await hasLinuxPytest(t))return;
  const adjacent={name:'verify_tests',args:{runner:'pytest',target:'test_adjacent.py'}};
  const result=await replay(t,{allowShell:true,serverEntry:true,workTransfer:true,
    adjacentSource:'from labels import label\ndef test_normal_value(): assert label("plain") == "plain"\n',
    obligations:[{...verify.args,role:'required'},{...adjacent.args,role:'required'}],
    actions:[{name:'read',args:{path:'labels.py'}},verify,edit('return value','return value.strip()'),verify,adjacent]});
  const tickets=result.events.filter(e=>e.type==='work_permission_required');
  assert.deepEqual(tickets.map(e=>e.action),['READ','VERIFY','PROPOSE_PATCH','VERIFY','VERIFY','VERIFY']);
  assert.equal(new Set(tickets.map(e=>e.requestId)).size,tickets.length);
  for(const request of result.requests){
    const names=request.tools.map(tool=>tool.function.name);
    assert.ok(names.includes('verify_tests'));
    assert.ok(names.every(name=>['list','read','write','edit','verify_tests'].includes(name)));
  }
  const original=await fs.readFile(path.join(result.workspace,'..','original-labels.py'),'utf8');
  assert.equal(original,'def label(value):\n    return value\n');
  assert.match(await fs.readFile(path.join(result.workspace,'labels.py'),'utf8'),/value\.strip\(\)/);
  const baseline=result.evidence.find(e=>e.observation?.target==='test_labels.py' && e.observation.status==='FAILED');
  assert.ok(baseline?.observation.testsRun>0);
  const final=result.evidence.at(-1);
  assert.notEqual(final.candidateHash,baseline.observation.snapshotHash);
  assert.equal(final.status,'PASSED');
  assert.equal(final.baselineHash,baseline.observation.snapshotHash);
  assert.deepEqual(final.obligations.map(o=>[o.target,o.status,o.testsRun]),[['test_labels.py','PASSED',1],['test_adjacent.py','PASSED',1]]);
  assert.ok(final.obligations.find(o=>o.target==='test_labels.py').baselineFailed);
  for(const item of final.obligations){
    const observed=result.evidence.findLast(e=>e.observation?.target===item.target).observation;
    assert.equal(observed.snapshotHash,final.candidateHash);
  }
  t.diagnostic(JSON.stringify({baselineHash:baseline.observation.snapshotHash,candidateHash:final.candidateHash,scope:final.scope,
    workTickets:tickets.map(e=>e.action),obligations:final.obligations.map(({runner,target,status,testsRun,targetHash,environment})=>({runner,target,status,testsRun,targetHash,environment}))}));
});

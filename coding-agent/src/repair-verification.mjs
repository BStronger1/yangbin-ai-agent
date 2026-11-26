// Count finished test cases, excluding skipped tests, suites and Node's implicit
// "the file exited successfully" test for a file with no registered cases.
const nodeReporter = `
import path from 'node:path';
export default async function* report(events) {
  let testsRun = 0;
  let failures = 0;
  let assertionFailures = 0;
  let executionFailures = 0;
  for await (const {type, data} of events) {
    if (type === 'test:stderr' || type === 'test:stdout') {
      process.stderr.write(data.message);
      continue;
    }
    if (type !== 'test:pass' && type !== 'test:fail') continue;
    const fileOnly = data.file && path.resolve(data.name) === path.resolve(data.file);
    if (!data.skip && !data.todo && !fileOnly && data.details?.type !== 'suite') testsRun++;
    if (type === 'test:fail' && !data.todo && !data.skip) {
      failures++;
      const error = data.details?.error;
      if (error?.code === 'ERR_ASSERTION' || error?.cause?.code === 'ERR_ASSERTION') assertionFailures++;
      else executionFailures++;
      yield data.name + ': ' + String(data.details?.error?.cause || data.details?.error || 'failed') + '\\n';
    }
  }
  yield '\\nYANGBIN_TEST_RESULT=' + JSON.stringify({testsRun, failures,assertionFailures,executionFailures,environment:'node/'+process.version+'/'+process.platform+'/'+process.arch}) + '\\n';
}`;

// Isolated supervisor; only it writes stdout. Repository logs go to stderr.
// The JUnit report is runner evidence for these repository-controlled tests.
const pytestDriver = `
import json, os, subprocess, sys, tempfile, xml.etree.ElementTree as xml
with tempfile.TemporaryDirectory() as tmp:
    report = os.path.join(tmp, "tests.xml")
    failure_report = os.path.join(tmp, "failure-kinds.json")
    bootstrap = """
import json, os, sys, pytest
sys.path.insert(0, os.getcwd())
failure_report = sys.argv.pop(1)
class FailureEvidence:
    assertion_failures = 0
    execution_failures = 0
    @pytest.hookimpl(hookwrapper=True)
    def pytest_runtest_makereport(self, item, call):
        outcome = yield
        report = outcome.get_result()
        if report.failed and report.when == 'call':
            if call.excinfo is not None and issubclass(call.excinfo.type, AssertionError):
                self.assertion_failures += 1
            else:
                self.execution_failures += 1
evidence = FailureEvidence()
code = pytest.main(sys.argv[1:], plugins=[evidence])
with open(failure_report, 'w') as target:
    json.dump(dict(assertionFailures=evidence.assertion_failures, executionFailures=evidence.execution_failures), target)
sys.exit(code)
"""
    code = subprocess.call([sys.executable, "-I", "-B", "-c", bootstrap, failure_report, "-q", "-p", "no:cacheprovider",
                            "--junitxml", report, sys.argv[1]], stdout=sys.stderr, stderr=sys.stderr)
    if os.path.isfile(report) and os.stat(report).st_size <= 4194304:
        cases = xml.parse(report).findall(".//testcase")
        failed = [case.find("failure") for case in cases if case.find("failure") is not None]
        # A dependency can also be imported lazily in the body of a test.
        unavailable = sum(f.get("message", "").startswith("ModuleNotFoundError: No module named ") for f in failed)
        failures = len(failed) - unavailable
        errors = sum(case.find("error") is not None for case in cases) + unavailable
        executed = sum(case.find("skipped") is None and case.find("error") is None for case in cases)
        try:
            from importlib.metadata import version
            pytest_version = version("pytest")
        except ImportError:
            pytest_version = "unreported"
        kinds = {}
        if os.path.isfile(failure_report) and os.stat(failure_report).st_size <= 1024:
            with open(failure_report) as source:
                kinds = json.load(source)
        print("YANGBIN_TEST_RESULT=" + json.dumps({"testsRun": executed, "failures": failures, "errors": errors, **kinds, "environment":"pytest/"+pytest_version+"/python/"+sys.version.split()[0]}))
sys.exit(code)`;

const djangoDriver = `
import json, os, re, subprocess, sys, tempfile
label = sys.argv[1][len('tests/'):-3].replace('/', '.')
with tempfile.TemporaryFile(mode='w+b') as log:
    bootstrap = "import os,runpy,sys; sys.path[:0]=[os.path.abspath('tests'),os.getcwd()]; sys.argv[0]='tests/runtests.py'; runpy.run_path(sys.argv[0],run_name='__main__')"
    code = subprocess.call([sys.executable,'-I','-B','-c',bootstrap,label,'--verbosity','1'],stdout=log,stderr=log,env=dict(os.environ,DJANGO_TEST_PROCESSES='1'))
    log.seek(max(0,log.tell()-12000))
    output=log.read().decode('utf8',errors='replace')
    print(output,file=sys.stderr)
    match=re.search(r'^Ran (\\d+) tests? in [^\\n]+',output,re.M)
    outcome=re.search(r'^(OK|FAILED)(?: \\(([^)]+)\\))?\\s*$',output,re.M)
    if match and outcome:
        counts=dict((key,int(value)) for key,value in re.findall(r'([a-z]+)=(\\d+)',outcome.group(2) or ''))
        print('YANGBIN_TEST_RESULT='+json.dumps(dict(testsRun=max(0,int(match.group(1))-counts.get('skipped',0)),failures=counts.get('failures',0),errors=counts.get('errors',0),environment='django/python/'+sys.version.split()[0])))
sys.exit(code)`;

export function normalizeTestTarget(runner, target) {
  if (typeof target !== 'string' || target.startsWith('/') || target.endsWith('/') || target.length > 300) {
    throw new Error('Choose a relative test file');
  }
  target = target.split('/').filter(part => part && part !== '.').join('/');
  if (!['node', 'pytest','django'].includes(runner) || !target.split('/').every(part => /^[\p{L}\p{N}_][\p{L}\p{N}_. -]*$/u.test(part)) ||
      !(runner === 'node' ? /\.[cm]?js$/ : /\.py$/).test(target) ||
      (runner === 'django' && !/^tests\/(?:\w+\/)*\w+\.py$/.test(target))) {
    throw new Error('Choose a single relative test file and its matching runner');
  }
  return target;
}
export function testInvocation(runner, file) {
  const target = normalizeTestTarget(runner, file);
  if (runner === 'node') {
    const reporter = 'data:text/javascript;base64,' + Buffer.from(nodeReporter).toString('base64');
    return ['node', '--test', '--test-reporter=' + reporter, './' + target];
  }
  if (runner === 'django') return ['python3','-I','-B','-c',djangoDriver,target];
  return ['python3', '-I', '-B', '-c', pytestDriver, './' + target];
}

export function interpretTestRun({code, stdout, stderr = ''}) {
  const match = /(?:^|\n)YANGBIN_TEST_RESULT=(\{[^\r\n]*\})\s*$/.exec(stdout);
  const diagnostic = (stderr + '\n' + (match ? stdout.slice(0,match.index) : stdout)).trim().slice(-4500);
  let counts;
  try { counts = match && JSON.parse(match[1]); } catch {}
  if (!counts || !Number.isSafeInteger(counts.testsRun) || counts.testsRun < 0 ||
      !Number.isSafeInteger(counts.failures) || counts.failures < 0) {
    return {status: 'BLOCKED', exitCode: code, testsRun: null, diagnostic};
  }
  const {testsRun, failures, errors = 0} = counts;
  let status = 'BLOCKED';
  if (errors === 0) {
    if (code === 0 && failures === 0) status = testsRun > 0 ? 'PASSED' : 'NO_TESTS';
    else if (code === 5 && testsRun === 0 && failures === 0) status = 'NO_TESTS';
    else if (code === 1 && testsRun > 0 && failures > 0) status = 'FAILED';
  }
  const environment=typeof counts.environment==='string' && /^[a-zA-Z0-9./_+-]{1,120}$/.test(counts.environment) ? counts.environment : null;
  const {assertionFailures, executionFailures} = counts;
  const classified = [assertionFailures,executionFailures].every(value=>Number.isSafeInteger(value) && value>=0) &&
    assertionFailures + executionFailures === failures;
  const failureKind = status!=='FAILED' ? null : !classified ? 'UNKNOWN' :
    assertionFailures===failures ? 'ASSERTION' : executionFailures===failures ? 'EXECUTION' : 'MIXED';
  return {status, testsRun, exitCode: code, diagnostic,failureKind,...(environment?{environment}:{})};
}

const empty = status => ({status, runner:null, target:null, testsRun:null, exitCode:null, snapshotHash:null, baselineFailed:false});
const priority = ['TEST_CHANGED','UNSTABLE','FAILED','BLOCKED','NO_TESTS','STALE','UNVERIFIED','PASSED'];

export class RepairVerification {
  constructor(emit, definitions = [], environment = 'server-sandbox', {testLifecycle = true} = {}) {
    this.emit = emit;
    this.testLifecycle = testLifecycle;
    this.environment = environment;
    this.revision = 0;
    this.generation = 0;
    this.activeWrites = 0;
    this.checks = new Map();
    this.runnerEnvironments=new Map();
    this.changedPaths = new Set();
    this.testPaths = new Set();
    this.candidateHash = null;
    this.latest = null;
    if (!Array.isArray(definitions) || definitions.length > 80) throw new Error('Invalid verification obligations');
    for (const item of definitions) {
      const target = normalizeTestTarget(item.runner,item.target);
      if (!['required','exploratory','counterexample'].includes(item.role)) throw new Error('Invalid verification role');
      this.define(item.runner,target,item.role).declared=true;
      this.testPaths.add(target);
    }
  }
  define(runner,target,role) {
    const key = JSON.stringify([runner,target]);
    if (!this.checks.has(key)) this.checks.set(key,{runner,target,role,history:[],definitionHash:null,baselineHash:null});
    return this.checks.get(key);
  }
  requested() { this.publish(); }
  beginWrite() {
    this.activeWrites++;
    this.generation++;
    if (this.candidateHash) this.publish('STALE');
  }
  endWrite(success, path) {
    this.activeWrites--;
    this.generation++;
    if (success) { this.revision++; this.changedPaths.add(path); }
    this.publish(this.candidateHash ? 'STALE' : 'UNVERIFIED');
  }
  beginCheck() { return this.generation; }
  protects(path) {
    return [...this.checks.values()].some(item=>item.target===path && item.role!=='exploratory' && (item.definitionHash || item.declared));
  }
  onlyTestChanged(target) {
    return this.changedPaths.size === 0 || (this.changedPaths.size === 1 && this.changedPaths.has(target));
  }
  hasProductionChanges() {
    // Unknown paths remain implementation changes until a real test execution
    // establishes them as tests. Merely naming a file test_*.py is not evidence.
    return [...this.changedPaths].some(target=>!this.testPaths.has(target));
  }
  checked(generation, runner, target, result) {
    if (this.testLifecycle) {
      try { target = normalizeTestTarget(runner,target); }
      catch { return this.publish(undefined,{runner,target,status:'BLOCKED',reason:'INVALID_TARGET'}); }
    }
    if (this.activeWrites || this.generation !== generation) {
      return this.publish('STALE',{runner,target,status:result.status,testsRun:result.testsRun??null,
        snapshotHash:result.snapshotHash??null,targetHash:result.targetHash??null,environment:result.environment??this.environment,
        stale:true});
    }
    const hasContract=[...this.checks.values()].some(check=>check.declared);
    const item = this.define(runner,target,hasContract || (this.testLifecycle && this.changedPaths.has(target)) ? 'exploratory' : 'required');
    if (item.authored===undefined) item.authored=this.changedPaths.has(target);
    // A failed runner without a snapshot cannot reuse a previous candidate's
    // hash; doing so would turn old baseline failures into current evidence.
    const candidate = result.snapshotHash || (this.testLifecycle ? null : this.candidateHash);
    const environment = result.environment || this.runnerEnvironments.get(runner) || this.environment;
    this.runnerEnvironments.set(runner,environment);
    const observation = {...result, diagnostic:undefined, environment, snapshotHash:candidate, revision:this.revision};
    if (['PASSED','FAILED'].includes(observation.status) && !(Number.isSafeInteger(observation.testsRun) && observation.testsRun > 0)) observation.status='NO_TESTS';
    if (this.testLifecycle) {
      const executed = ['PASSED','FAILED'].includes(observation.status);
      if (executed) this.testPaths.add(target);
      const explicitExploration = item.declared && item.role==='exploratory';
      const baselineAssertion = observation.status==='FAILED' && result.failureKind==='ASSERTION' && !this.hasProductionChanges();
      if (result.targetHash && !item.definitionHash && !explicitExploration &&
          (!item.authored || item.declared || baselineAssertion)) {
        item.definitionHash=result.targetHash;
        if (item.authored && !item.declared) item.role='required';
      }
      // An absent definition is unestablished evidence, not a changed definition.
      // Preserve environmental failures even when an independent hash conflict exists.
      if (result.targetHash && item.definitionHash && item.definitionHash!==result.targetHash) {
        observation.definitionChanged=true;
        if (executed) observation.status='TEST_CHANGED';
      }
    } else {
      // Frozen behavior is retained only for the independent comparison switch.
      if (result.targetHash && !item.definitionHash && (!this.changedPaths.has(target) ||
          (observation.status === 'FAILED' && this.onlyTestChanged(target)))) item.definitionHash = result.targetHash;
      if (result.targetHash && item.definitionHash !== result.targetHash) observation.status='TEST_CHANGED';
    }
    const baselineEligible = this.testLifecycle ? !this.hasProductionChanges() && Boolean(item.definitionHash) &&
      observation.failureKind==='ASSERTION' : this.onlyTestChanged(target);
    if (baselineEligible && observation.status==='FAILED' && candidate && !item.baselineHash) {
      item.baselineHash=candidate;
      item.baselineEnvironment=environment;
    }
    item.history.push(observation);
    this.candidateHash=candidate;
    return this.publish(undefined,{runner,target,...observation});
  }
  summarize(item) {
    const environment=this.runnerEnvironments.get(item.runner) || this.environment;
    const current = item.history.filter(obs => obs.snapshotHash === this.candidateHash && obs.environment === environment);
    const latest = current.at(-1);
    const statuses = new Set(current.map(obs=>obs.status));
    let status = latest?.status || (item.history.length ? 'STALE' : 'UNVERIFIED');
    if (statuses.has('TEST_CHANGED')) status='TEST_CHANGED';
    else if (statuses.has('FAILED') && statuses.has('PASSED')) status='UNSTABLE';
    else if (statuses.has('FAILED')) status='FAILED';
    return {runner:item.runner,target:item.target,role:item.role,status,testsRun:latest?.testsRun??null,exitCode:latest?.exitCode??null,
      targetHash:item.definitionHash,environment,baselineHash:item.baselineHash,
      baselineFailed:Boolean(status!=='TEST_CHANGED' && item.baselineEnvironment===environment && item.baselineHash && this.candidateHash && this.candidateHash !== item.baselineHash &&
        (this.testLifecycle ? this.hasProductionChanges() : !this.onlyTestChanged(item.target))),
      observations:current.map(({status,testsRun,exitCode,targetHash})=>({status,testsRun,exitCode,targetHash}))};
  }
  publish(forced,observation) {
    const obligations = [...this.checks.values()].map(item=>{
      const summary=this.summarize(item);
      if(forced==='STALE' && item.history.length)return {...summary,status:'STALE',previousStatus:summary.status,observedCandidateHash:this.candidateHash};
      return summary;
    });
    const hasContract=[...this.checks.values()].some(check=>check.declared);
    const required=obligations.filter(item=>item.role==='required' || (item.role==='counterexample' && ['FAILED','UNSTABLE','TEST_CHANGED'].includes(item.status)));
    // Without a declared contract, display exploratory observations honestly,
    // while their role and baselineFailed flag still limit what they establish.
    if (this.testLifecycle && !hasContract && required.length===0) required.push(...obligations);
    const selected = required.sort((a,b)=>priority.indexOf(a.status)-priority.indexOf(b.status) ||
      Number(b.baselineFailed)-Number(a.baselineFailed) || String(a.target).localeCompare(String(b.target)))[0];
    const status=forced || selected?.status || 'UNVERIFIED';
    this.latest={type:'repair_verification',...empty(status), ...(selected || {}),status, revision:this.revision,
      scope:[...this.checks.values()].some(check=>check.declared)?'declared_obligations':'observed_tests',
      snapshotHash:forced ? null : this.candidateHash,candidateHash:forced ? null : this.candidateHash,
      baselineHash:selected?.baselineHash??null,obligations, ...(observation ? {observation} : {})};
    this.emit(this.latest);
    return this.latest;
  }
  async refresh(hashWorkspace) {
    if (!this.candidateHash || this.activeWrites) return;
    const generation=this.generation;
    let current;
    try { current=await hashWorkspace(); } catch {}
    if (this.activeWrites || generation!==this.generation) return;
    if (current !== this.candidateHash) this.publish('STALE');
    else if (this.latest.status === 'STALE') this.publish();
  }
  needsCorrection() {
    return ['FAILED','UNSTABLE','TEST_CHANGED'].includes(this.latest?.status) &&
      (this.testLifecycle ? this.hasProductionChanges() : !this.onlyTestChanged(this.latest.target));
  }
}

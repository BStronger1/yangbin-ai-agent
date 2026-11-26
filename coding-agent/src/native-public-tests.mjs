import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
import {interpretTestRun, normalizeTestTarget, testInvocation} from './repair-verification.mjs';

const exec = promisify(execFile);
const supervisor = fileURLToPath(new URL('public_test_sandbox.py',import.meta.url));

// Called by the trusted native host. Model-supplied fields can only select a test.
export async function nativePublicTest({workspace,dependencyRoot,publicSourceManifest,signal}, args) {
  const {runner} = args;
  const target = normalizeTestTarget(runner, args.target);
  const invocation = testInvocation(runner,target);
  let stdout;
  try {
    ({stdout} = await exec('/usr/bin/python3',['-I','-B',supervisor,workspace,publicSourceManifest || '-',dependencyRoot,target,...invocation],{
      env:{PATH:'/usr/bin:/bin',LANG:'C.UTF-8'},signal,timeout:120000,maxBuffer:196608,windowsHide:true
    }));
  } catch (error) {
    signal?.throwIfAborted();
    const knownReason = /ValueError: (Selected public test file is missing|No pinned Python environment in dependency image|Public test snapshot file limit reached|Public test snapshot exceeds 1 GiB|Published dependency differs from its verified public source)/.exec(error.stderr || '');
    throw new Error(knownReason?.[1] || 'Public test environment unavailable or time limit reached');
  }
  const response = JSON.parse(stdout);
  return {...interpretTestRun(response), snapshotHash:response.snapshotHash,targetHash:response.targetHash};
}

export async function nativePublicTestHash({workspace,publicSourceManifest,signal}) {
  const {stdout} = await exec('/usr/bin/python3',['-I','-B',supervisor,workspace,publicSourceManifest || '-','--hash'],{
    env:{PATH:'/usr/bin:/bin',LANG:'C.UTF-8'},signal,timeout:60000,maxBuffer:1024,windowsHide:true
  });
  const value = stdout.trim();
  if (!/^[0-9a-f]{64}$/.test(value)) throw new Error('Invalid public test snapshot fingerprint');
  return value;
}

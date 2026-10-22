"""Read-only host inventory and opt-in bounded acceptance of the actual sandbox module.
Never installs packages, reads credentials, calls a model, changes services or enables shell.
Success is evidence, not approval: task-wide resource containment still needs verification.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import sys
import tempfile

FLAGS = ('--clearenv', '--size', '--unshare-all', '--die-with-parent',
         '--new-session', '--cap-drop', '--remount-ro')


def command(args):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=5,
                           env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'})
        return {'code': r.returncode, 'output': (r.stdout + r.stderr)[:16000]}
    except (OSError, subprocess.TimeoutExpired):
        return {'code': None, 'output': ''}


def read_optional(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None


def inventory(bwrap):
    tools = {}
    for tool in ('cc', 'make', 'meson', 'ninja', 'pkg-config', 'prlimit'):
        executable = shutil.which(tool)
        tools[tool] = {'available': bool(executable)}
        if executable:
            tools[tool]['version'] = command([executable, '--version'])['output'].splitlines()[:1]
    help_output = command([bwrap, '--help'])['output']
    flags = {flag: flag in help_output for flag in FLAGS}
    return {
        'platform': platform.system(), 'kernel': platform.release(),
        'bwrap_version': command([bwrap, '--version'])['output'].strip(),
        'required_flags': flags, 'modern_bwrap_flags_present': all(flags.values()),
        'tools': tools,
        'unprivileged_userns': read_optional('/proc/sys/kernel/unprivileged_userns_clone'),
        'max_user_namespaces': read_optional('/proc/sys/user/max_user_namespaces'),
        'cgroup_membership': read_optional('/proc/self/cgroup'),
        'cgroup_v2_controllers': read_optional('/sys/fs/cgroup/cgroup.controllers'),
        'cgroup_root_writable': os.access('/sys/fs/cgroup', os.W_OK),
        'cgroup_delegation_verified': False,
        'libcap_pkgconfig': command(['pkg-config', '--exists', 'libcap'])['code'] == 0,
    }


# Exercise exactly the deployed runner; do not duplicate its isolation flags.
# Emit booleans only, never file contents, command output, or process arguments.
HARNESS = r'''
import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import crypto from 'node:crypto';
const {sandboxStatus,runIsolatedCommand}=await import(pathToFileURL(process.argv[1]));
const workspace=process.argv[2], checks=[];
const add=(name,passed)=>checks.push({name,passed:Boolean(passed)});
const status=await sandboxStatus({refresh:true});
if(!status.available){console.log(JSON.stringify({available:false,reason:status.reason,checks}));process.exit(2);}
const run=(command,seconds=4)=>runIsolatedCommand({workspace,command,timeoutSeconds:seconds});
async function successful(name,command){
 try{add(name,(await run(command)).startsWith('Exit code: 0\n'));}catch{add(name,false);}
}
await fs.writeFile(path.join(workspace,'fixture.txt'),'synthetic');
await fs.writeFile(path.join(workspace,'.env'),'synthetic-not-a-secret');
await successful('read_only_and_private_paths','test "$(id -u)" = 65534 && test -r fixture.txt && test ! -e .env && test ! -e /home && test ! -e /etc && test ! -e /sys && test ! -e /run && ! touch /root-write && ! touch /usr/local/local-write && ! touch /dev/dev-write && ! touch /workspace/forbidden && touch /tmp/allowed');
await successful('minimal_environment','test -z "$PREFLIGHT_SECRET_CANARY" && test "$HOME" = /tmp && test "$PATH" = /usr/bin:/bin');
await successful('no_external_route',`python3 -c 'import socket
s=socket.socket();s.settimeout(0.5)
try:
 s.connect(("192.0.2.1",9));raise SystemExit(1)
except OSError: pass
'`);
await successful('proc_namespace',`python3 -c 'from pathlib import Path
assert all(int(p.name)<100 for p in Path("/proc").iterdir() if p.name.isdigit())
'`);
await successful('address_space_limit',`python3 -c 'import mmap
try:
 m=mmap.mmap(-1,1073741825);raise SystemExit(1)
except (OSError,OverflowError): pass
'`);
await successful('declared_resource_limits',`python3 -c 'import resource
assert resource.getrlimit(resource.RLIMIT_AS)[0]==1073741824
assert resource.getrlimit(resource.RLIMIT_CPU)[0]<=4
assert resource.getrlimit(resource.RLIMIT_NPROC)[0]==64
assert resource.getrlimit(resource.RLIMIT_NOFILE)[0]==128
assert resource.getrlimit(resource.RLIMIT_FSIZE)[0]==16777216
'`);
// At most 80 MiB attempted, never an unbounded fill.
await successful('tmpfs_capacity',`python3 -c 'import errno
chunk=b"x"*1048576
try:
 for i in range(5):
  with open("/tmp/f"+str(i),"wb") as f:
   for j in range(16):f.write(chunk)
 raise SystemExit(1)
except OSError as e: assert e.errno==errno.ENOSPC
'`);
try{await run("python3 -c 'print(\"x\"*70000)'");add('output_limit',false);}
catch(error){add('output_limit',error.message.includes('output limit'));}
const start=Date.now();
try{await run('sleep 4',1);add('wall_timeout',false);}
catch(error){add('wall_timeout',error.message.includes('timed out')&&Date.now()-start<3500);}
const marker='m025-descendant-'+crypto.randomUUID(),controller=new AbortController();
async function descendantCount(){
 let count=0;
 for(const entry of await fs.readdir('/proc')){
  if(!/^\d+$/.test(entry))continue;
  try{if((await fs.readFile(`/proc/${entry}/cmdline`,'utf8')).split('\0').includes(marker))count++;}catch{}
 }
 return count;
}
// Observe an actual detached child before cancelling; a pre-launch abort is insufficient.
const task=runIsolatedCommand({workspace,signal:controller.signal,timeoutSeconds:4,
 command:`python3 -c 'import subprocess,time;subprocess.Popen(["python3","-c","import time;time.sleep(4)","${marker}"],start_new_session=True);time.sleep(4)'`})
 .then(()=>false,error=>error.message.includes('cancelled'));
let observed=false;
for(let i=0;i<30;i++){
 if(await descendantCount()){observed=true;break;}
 await new Promise(resolve=>setTimeout(resolve,50));
}
add('cancel_descendant_observed',observed);
controller.abort();
add('cancel_rejected',await task);
await new Promise(resolve=>setTimeout(resolve,150));
add('cancel_no_detached_descendants',(await descendantCount())===0);
await fs.symlink('/etc/passwd',path.join(workspace,'outside-link'));
try{await run('true');add('symlink_rejected',false);}
catch(error){add('symlink_rejected',error.message.includes('linked paths'));}
await fs.unlink(path.join(workspace,'outside-link'));
add('live_workspace_unchanged',(await fs.readFile(path.join(workspace,'fixture.txt'),'utf8'))==='synthetic'&&!(await fs.readdir(workspace)).includes('forbidden'));
console.log(JSON.stringify({available:true,checks,passed:checks.every(c=>c.passed),aggregateResourceContainmentVerified:false}));
'''


def exercise(node, runtime, bwrap):
    if platform.system() != 'Linux':
        return {'passed': False, 'reason': 'Linux target required'}
    node_path, runtime_path = Path(node), Path(runtime)
    if not node_path.is_absolute() or not runtime_path.is_absolute():
        return {'passed': False, 'reason': 'Absolute node and runtime paths required'}
    if not node_path.is_file() or not runtime_path.is_file():
        return {'passed': False, 'reason': 'Node or runtime file missing'}
    with tempfile.TemporaryDirectory(prefix='m025-sandbox-acceptance-') as workspace:
        env = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'CODING_BWRAP': bwrap,
               'PREFLIGHT_SECRET_CANARY': 'synthetic'}
        process = subprocess.Popen([str(node_path), '--input-type=module', '-e', HARNESS,
                                    str(runtime_path), workspace], env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True, start_new_session=True)
        try:
            stdout, _ = process.communicate(timeout=50)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
            return {'passed': False, 'reason': 'Acceptance harness deadline exceeded'}
        try:
            result = json.loads(stdout)
            if not isinstance(result, dict):
                raise ValueError('Unexpected harness result')
        except (ValueError, TypeError):
            return {'passed': False, 'reason': 'Acceptance harness returned invalid JSON',
                    'exit_code': process.returncode}
        result['runtime_sha256'] = hashlib.sha256(runtime_path.read_bytes()).hexdigest()
        return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bwrap', default='/usr/bin/bwrap')
    parser.add_argument('--exercise', action='store_true')
    parser.add_argument('--node')
    parser.add_argument('--runtime')
    args = parser.parse_args(argv)
    if not Path(args.bwrap).is_absolute():
        parser.error('--bwrap must be an absolute administrator-controlled path')
    if args.exercise and not (args.node and args.runtime):
        parser.error('--exercise requires --node and --runtime')
    report = {'inventory': inventory(args.bwrap), 'exercised': False,
              'terminal_enablement_approved': False,
              'remaining': ['Task-wide memory/CPU/PID containment is not established by prlimit',
                            'No production configuration is changed by this probe']}
    if args.exercise:
        if not report['inventory']['modern_bwrap_flags_present']:
            report['acceptance'] = {'passed': False, 'reason': 'Required bubblewrap flags absent'}
        else:
            report['exercised'] = True
            report['acceptance'] = exercise(args.node, args.runtime, args.bwrap)
    print(json.dumps(report, indent=2))
    return 0 if not args.exercise else (0 if report['acceptance'].get('passed') else 2)


if __name__ == '__main__':
    sys.exit(main())

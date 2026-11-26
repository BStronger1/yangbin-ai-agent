"""Snapshot granted files and run a fixed test command in an offline user namespace."""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile


def protected(name):
    return re.search(r'^(\.git|\.ssh|\.aws|\.codex|\.pi|node_modules)$|^\.env(\.|$)|^(credentials|auth|secrets)(\.|$)|\.(pem|key|p12|pfx)$', name, re.I)


def snapshot(workspace, published, dest=None, target=None):
    fingerprint = hashlib.sha256()
    found = None
    total = count = 0
    for root, dirs, names, fd in os.fwalk(workspace, follow_symlinks=False):
        relative = os.path.relpath(root, workspace)
        prefix = '' if relative == '.' else relative + '/'
        dirs[:] = sorted(d for d in dirs if (not protected(d) or
                         (prefix+d == 'django/contrib/auth' and published)) and
                         stat.S_ISDIR(os.stat(d, dir_fd=fd, follow_symlinks=False).st_mode))
        if dest:
            (dest / relative).mkdir(exist_ok=True)
            (dest / relative).chmod(0o755)
        fingerprint.update(os.fsencode(prefix) + b'\0')
        for name in sorted(names):
            if protected(name):
                continue
            st = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if not stat.S_ISREG(st.st_mode) or st.st_nlink != 1:
                continue
            count += 1
            if count > 100000 or st.st_size > 256 * 1024**2:
                raise ValueError('Public test snapshot file limit reached')
            rel = prefix + name
            if rel.startswith('django/contrib/auth/') and rel not in published:
                continue
            item = hashlib.sha256()
            with os.fdopen(os.open(name, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=fd), 'rb') as src:
                opened = os.fstat(src.fileno())
                if (st.st_dev,st.st_ino) != (opened.st_dev,opened.st_ino) or opened.st_nlink != 1:
                    raise ValueError('Source changed while snapshotting')
                out = (dest / rel).open('xb') if dest else None
                try:
                    while True:
                        block = src.read(1024**2)
                        if not block:
                            break
                        total += len(block)
                        if total > 1024**3:
                            raise ValueError('Public test snapshot exceeds 1 GiB')
                        if out:
                            out.write(block)
                        item.update(block)
                finally:
                    if out:
                        out.close()
            if rel.startswith('django/contrib/auth/') and item.hexdigest() != published[rel]:
                raise ValueError('Published dependency differs from its verified public source')
            if dest:
                (dest / rel).chmod(0o444 | (st.st_mode & 0o111))
            fingerprint.update(os.fsencode(rel) + b'\0' + bytes([st.st_mode & 0o111]) + item.digest())
            if rel == target:
                found = item.hexdigest()
    return fingerprint.hexdigest(), found


def parent_death_signal():
    # Kill the whole namespace on timeout, cancellation, or loss of the supervisor.
    if ctypes.CDLL(None).prctl(1, signal.SIGKILL, 0, 0, 0) or os.getppid() == 1:
        os._exit(1)


def main():
    workspace, manifest, dependency = sys.argv[1:4]
    published = {}
    if manifest != '-':
        reference = Path(manifest).resolve(strict=True)
        if Path(workspace).resolve() in reference.parents or reference.stat().st_size > 1024**2:
            raise ValueError('Invalid public source manifest')
        published = json.loads(reference.read_bytes())
        if not isinstance(published,dict) or any(not k.startswith('django/contrib/auth/') or
            '..' in Path(k).parts or not isinstance(v,str) or not re.fullmatch('[0-9a-f]{64}',v) for k,v in published.items()):
            raise ValueError('Invalid public source digests')
    if dependency == '--hash':
        print(snapshot(workspace,published)[0])
        return
    image = Path(dependency).resolve(strict=True)
    target, executable, *arguments = sys.argv[4:]
    if executable != 'python3':
        raise ValueError('Only Python public tests are supported in this environment')
    clean = {'PATH':'/usr/bin:/bin', 'LANG':'C.UTF-8'}
    with tempfile.TemporaryDirectory(prefix='swe-public-test-') as scratch:
        root = Path(scratch)
        source = root/'source'
        source.mkdir()
        snapshot_hash, target_hash = snapshot(workspace, published, source, target)
        if target_hash is None:
            raise ValueError('Selected public test file is missing')
        conda = '/opt/miniconda3'
        prefix = conda+'/envs/testbed' if (image / (conda[1:]+'/envs/testbed/bin/python')).is_file() else conda
        python = prefix+'/bin/python'
        if not (image / python[1:]).is_file():
            raise ValueError('No pinned Python environment in dependency image')
        # Never bind the image root, its repository, host home, scorer, or model state.
        cmd = ['/usr/bin/bwrap','--unshare-all','--die-with-parent','--new-session','--cap-drop','ALL']
        for part in ('usr','lib','lib64','bin','sbin'):
            src = image/part
            if src.is_symlink():
                link = os.readlink(src)
                if not re.fullmatch(r'(?:/)?usr/(?:bin|sbin|lib|lib64)',link):
                    raise ValueError('Unexpected runtime link')
                cmd.extend(['--symlink',link,'/'+part])
            elif src.is_dir():
                cmd.extend(['--ro-bind',str(src),'/'+part])
        for part in ('bin','lib','share','include'):
            src = image / prefix[1:] / part
            if src.is_dir():
                if image not in src.resolve().parents:
                    raise ValueError('Runtime directory escapes image')
                cmd.extend(['--ro-bind',str(src),prefix+'/'+part])
        tmp = root/'tmp'
        tmp.mkdir()
        cmd.extend(['--ro-bind',str(source),'/testbed','--proc','/proc','--dev','/dev',
                    '--bind',str(tmp),'/tmp','--remount-ro','/dev','--remount-ro','/proc','--remount-ro','/',
                    '--chdir','/testbed','--setenv','PATH',prefix+'/bin:/usr/bin:/bin','--setenv','HOME','/tmp',
                    '--setenv','TMPDIR','/tmp','--setenv','OPENBLAS_NUM_THREADS','1','--setenv','OMP_NUM_THREADS','1',
                    '--',python,*arguments])
        # A private bounded scratch mount works on bubblewrap 0.4 as well.
        wrapper = ['unshare','--user','--map-root-user','--mount','--propagation','private',
                   '--fork','--kill-child=KILL','/bin/sh','-ec',
                   'mount -t tmpfs -o size=64m,nodev,nosuid,mode=1777 tmpfs "$1"; shift; exec "$@"',
                   'public-tests',str(tmp),'/usr/bin/prlimit','--as=4294967296','--cpu=60','--nproc=96',
                   '--nofile=256','--fsize=16777216','--',*cmd]
        with (root/'stdout').open('w+b') as out, (root/'stderr').open('w+b') as err:
            run = subprocess.run(wrapper, stdin=subprocess.DEVNULL,stdout=out,stderr=err,env=clean,
                                 preexec_fn=parent_death_signal,timeout=90)
            def tail(stream, limit):
                stream.seek(max(0,stream.tell()-limit))
                return stream.read().decode('utf8',errors='replace')
            print(json.dumps(dict(snapshotHash=snapshot_hash,targetHash=target_hash,code=run.returncode,
                                  stdout=tail(out,8192),stderr=tail(err,12288))))


if __name__ == '__main__':
    main()

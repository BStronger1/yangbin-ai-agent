"""Restore immutable pi dependencies on local disk instead of importing thousands of files over NFS."""
import hashlib,os,stat,tarfile,tempfile
from pathlib import Path

def prepare(root,env):
    archive_value=env.get('CODING_RUNTIME_ARCHIVE')
    if env.get('CODING_ENABLED')!='true' or not archive_value:return
    archive=Path(archive_value).resolve();allowed=(root/'runtime/coding-releases').resolve()
    if not archive.is_relative_to(allowed) or not archive.is_file():raise RuntimeError('Invalid coding runtime archive')
    expected=env.get('CODING_RUNTIME_SHA256','')
    if len(expected)!=64 or any(c not in '0123456789abcdef' for c in expected):raise RuntimeError('Missing coding runtime checksum')
    cache=Path('/tmp')/('yangbin-agent-coding-'+str(os.getuid()))
    cache.mkdir(mode=0o700,exist_ok=True)
    info=cache.lstat()
    if stat.S_ISLNK(info.st_mode) or info.st_uid!=os.getuid() or info.st_mode&0o077:raise RuntimeError('Untrusted coding runtime cache')
    target=cache/expected
    if target.is_symlink():raise RuntimeError('Linked coding runtime cache')
    marker=target/'.complete'
    if not marker.is_file():
        if target.exists():raise RuntimeError('Incomplete coding runtime cache')
        if hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:raise RuntimeError('Coding runtime checksum mismatch')
        stage=Path(tempfile.mkdtemp(prefix='extract-',dir=cache))
        with tarfile.open(archive) as bundle:bundle.extractall(stage,filter='data')
        if not (stage/'src/main.mjs').is_file():raise RuntimeError('Coding runtime entry missing')
        (stage/'.complete').write_text(expected);stage.rename(target)
    if marker.read_text()!=expected:raise RuntimeError('Coding runtime cache mismatch')
    env['CODING_ENTRY']=str(target/'src/main.mjs')

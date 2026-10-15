"""Update this personal installation; retain runtime data and roll back on readiness failure.
Run with Python 3.12: python update.py /absolute/path/to/workspace-release.tar.gz
"""
import json,os,shutil,subprocess,sys,tarfile,time,urllib.request,uuid
from pathlib import Path
ROOT=Path.home()/"apps/yangbin-ai-agent"
os.umask(0o077)
archive=Path(sys.argv[1]).resolve()
if archive.parent!=ROOT.resolve() or not archive.is_file():raise SystemExit("Release must be a file inside this app directory")
stage=ROOT/("incoming-"+uuid.uuid4().hex[:8]);stage.mkdir(mode=0o700)
with tarfile.open(archive) as release:release.extractall(stage,filter="data")
for name in ("app.jar","frontend/index.html","deploy/turing/start.sh"):
 if not (stage/name).is_file():raise SystemExit("Incomplete release: "+name)
backup=ROOT/"releases"/("before-workspace-"+uuid.uuid4().hex[:8]);backup.mkdir(parents=True,mode=0o700)
for name in ("app.jar","frontend","deploy"):
 source=ROOT/name
 if source.is_dir():shutil.copytree(source,backup/name)
 else:shutil.copy2(source,backup/name)
def running():return subprocess.run(["tmux","has-session","-t","=yangbin-ai-agent"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
def stop():
 if not running():return
 subprocess.run(["tmux","send-keys","-t","yangbin-ai-agent:0.0","C-c"],check=True)
 for _ in range(120):
  if not running():return
  time.sleep(.5)
 raise RuntimeError("Service did not stop")
def replace(source):
 for name in ("app.jar","frontend","deploy"):
  target=ROOT/name
  if target.parent.resolve()!=ROOT.resolve():raise RuntimeError("Unexpected target")
  if (source/name).is_dir():
   if target.exists():shutil.rmtree(target)
   shutil.copytree(source/name,target)
  else:shutil.copy2(source/name,target)
def start():subprocess.run(["bash",str(ROOT/"deploy/turing/start.sh")],check=True)
def ready():
 env=json.loads((ROOT/"secrets.json").read_text())["env"]
 request=urllib.request.Request("http://127.0.0.1:18126/api/ai/knowledge",headers={"X-Internal-Token":env["INTERNAL_API_TOKEN"]})
 for _ in range(150):
  try:
   with urllib.request.urlopen(request,timeout=2) as response:
    if response.status==200 and isinstance(json.load(response),list):return
  except Exception:pass
  time.sleep(1)
 raise RuntimeError("New application did not become ready")
stop()
try:
 replace(stage);start();ready();print(json.dumps({"updated":True,"backup":str(backup)}),flush=True)
except Exception:
 stop();replace(backup);start();print("Rolled back to previous release",flush=True);raise

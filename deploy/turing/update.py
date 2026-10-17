"""Update this personal installation; retain runtime data and roll back on readiness failure.
Run with Python 3.12: python update.py /absolute/path/to/workspace-release.tar.gz
"""
import hashlib,json,os,shutil,subprocess,sys,tarfile,tempfile,time,urllib.request,uuid
from pathlib import Path
ROOT=Path.home()/"apps/yangbin-ai-agent"
os.umask(0o077)
archive=Path(sys.argv[1]).resolve()
if archive.parent!=ROOT.resolve() or not archive.is_file():raise SystemExit("Release must be a file inside this app directory")
stage=ROOT/("incoming-"+uuid.uuid4().hex[:8]);stage.mkdir(mode=0o700)
with tarfile.open(archive) as release:release.extractall(stage,filter="data")
for name in ("app.jar","frontend/index.html","deploy/turing/start.sh"):
 if not (stage/name).is_file():raise SystemExit("Incomplete release: "+name)
manifest_path=stage/"release.json"
manifest=json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
for name,expected in manifest.get("sha256",{}).items():
 item=(stage/name).resolve()
 if not item.is_relative_to(stage.resolve()) or not item.is_file():raise SystemExit("Invalid manifest path")
 if hashlib.sha256(item.read_bytes()).hexdigest()!=expected:raise SystemExit("Release checksum mismatch: "+name)
# Install the optional immutable coding runtime before interrupting the live service.
# Each release keeps its own node_modules; rollback only restores the prior config.
coding_entry=None;coding_archive=None;coding_checksum=None
if manifest.get("codingRuntime"):
 relative=manifest["codingRuntime"]["path"]
 expected="runtime/coding-releases/"+manifest["releaseId"]
 if relative!=expected:raise SystemExit("Invalid coding runtime path")
 source=(stage/relative).resolve();destination=(ROOT/relative).resolve()
 if not source.is_relative_to(stage) or not destination.is_relative_to(ROOT/"runtime/coding-releases"):raise SystemExit("Invalid runtime destination")
 node=ROOT/"runtime/coding-node/bin/node"
 npm=ROOT/"runtime/coding-node/lib/node_modules/npm/bin/npm-cli.js"
 if not node.is_file():raise SystemExit("Install compatible project Node before publishing")
 if destination.exists():raise SystemExit("Coding release already installed; inspect existing release before retry")
 install_env=dict(os.environ,PATH=str(node.parent)+os.pathsep+os.environ.get("PATH",""))
 print("Installing isolated pi runtime before service update",flush=True)
 # NFS metadata is slow; install on local disk, preserve one durable archive, restore on boot.
 local=Path(tempfile.mkdtemp(prefix="yangbin-coding-install-",dir="/tmp"))
 local_source=local/"coding";shutil.copytree(source,local_source)
 install_env["npm_config_cache"]=str(local/"npm-cache")
 subprocess.run([str(node),str(npm),"ci","--ignore-scripts","--no-audit","--no-fund"],cwd=local_source,env=install_env,check=True,timeout=900)
 subprocess.run([str(node),str(local_source/"src/main.mjs"),"--check"],env=install_env,check=True,timeout=120)
 subprocess.run([str(node),"--test",*[str(p.relative_to(local_source)) for p in sorted((local_source/"test").glob("*.test.mjs"))]],cwd=local_source,env=install_env,check=True,timeout=180)
 destination.parent.mkdir(parents=True,exist_ok=True);source.rename(destination)
 coding_archive=destination.with_suffix(".tar.gz")
 local_archive=local/"runtime.tar.gz"
 with tarfile.open(local_archive,"w:gz") as bundle:
  for child in local_source.iterdir():bundle.add(child,arcname=child.name)
 coding_checksum=hashlib.sha256(local_archive.read_bytes()).hexdigest()
 shutil.copyfile(local_archive,coding_archive)
 shutil.rmtree(local)
 coding_entry=destination/"src/main.mjs"
backup=ROOT/"releases"/("before-workspace-"+uuid.uuid4().hex[:8]);backup.mkdir(parents=True,mode=0o700)
for name in ("app.jar","frontend","deploy"):
 source=ROOT/name
 if source.is_dir():shutil.copytree(source,backup/name)
 else:shutil.copy2(source,backup/name)
shutil.copy2(ROOT/"secrets.json",backup/"secrets.json")
if (ROOT/"release.json").exists():shutil.copy2(ROOT/"release.json",backup/"release.json")
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
 request=urllib.request.Request("http://127.0.0.1:18126/api/ai/knowledge",headers={"X-Internal-Token":env["INTERNAL_API_TOKEN"],"X-User-Id":"admin","X-User-Role":"admin"})
 deadline=time.monotonic()+900
 while time.monotonic()<deadline:
  try:
   with urllib.request.urlopen(request,timeout=2) as response:
    if response.status==200 and isinstance(json.load(response),list):
     with urllib.request.urlopen("http://"+env["PUBLIC_BIND"]+":"+env["PUBLIC_PORT"]+"/api/auth/me",timeout=2) as auth:
      if auth.status==200 and json.load(auth).get("user") is None:
       if manifest.get("codingRuntime"):
        headers={"X-Internal-Token":env["INTERNAL_API_TOKEN"],"X-User-Id":"admin","X-User-Role":"admin"}
        coding=urllib.request.Request("http://127.0.0.1:18126/api/ai/coding/capabilities?modelProfileId=system",headers=headers)
        with urllib.request.urlopen(coding,timeout=10) as result:
         capability=json.load(result)
         configured=bool(env.get("CODING_MODEL_API_KEY") or env.get("MODEL_API_KEY"))
         if not isinstance(capability.get("available"),bool) or (configured and not capability["available"]):raise RuntimeError("Coding runtime not ready")
        models=urllib.request.Request("http://127.0.0.1:18126/api/ai/models",headers=headers)
        with urllib.request.urlopen(models,timeout=10) as result:
         if not isinstance(json.load(result).get("profiles"),list):raise RuntimeError("Model profiles not ready")
       return
  except Exception:pass
  time.sleep(1)
 raise RuntimeError("New application did not become ready")
stop()
try:
 # Services are stopped so SQLite and task/checkpoint files are consistent.
 for name in ("tmp","knowledge"):
  if (ROOT/name).exists():shutil.copytree(ROOT/name,backup/name,symlinks=True)
 data_hashes={str(p.relative_to(backup)):hashlib.sha256(p.read_bytes()).hexdigest() for folder in ("tasks","plans","repairs","pdf","skills","chat-memory-v2","work","coding","model-profiles") for p in (backup/"tmp"/folder).rglob("*") if p.is_file()}
 (backup/"data-sha256.json").write_text(json.dumps(data_hashes))
 config=json.loads((ROOT/"secrets.json").read_text())
 if manifest.get("narrativeEmbed"):
  ppt=json.loads((ROOT.parent/"yangbin-agent-ppt/secrets.json").read_text())
  config["env"].update(NARRATIVE_PORT="39811",AGENT_BRIDGE_SECRET=ppt["bridge"])
 previous_revision=config["env"].get("APP_BUILD_REVISION", "")
 if manifest.get("releaseId"):config["env"]["APP_BUILD_REVISION"]=manifest["releaseId"]
 config["env"]["PRESENTATION_FONT"]="Noto Sans CJK SC"
 if coding_entry:
  # M015 normalized its shared fallback with /v1. Preserve that legacy endpoint
  # once during upgrade; new user BYOK URLs are always kept exactly as configured.
  if previous_revision.startswith("m015-") and not config["env"].get("CODING_MODEL_BASE_URL"):
   legacy=config["env"].get("MODEL_BASE_URL", "").strip().rstrip("/")
   if legacy:config["env"]["CODING_MODEL_BASE_URL"]=legacy if legacy.endswith("/v1") else legacy+"/v1"
  config["env"].update(CODING_ENABLED="true",CODING_NODE=str(ROOT/"runtime/coding-node/bin/node"),CODING_ENTRY=str(coding_entry),CODING_RUNTIME_ARCHIVE=str(coding_archive),CODING_RUNTIME_SHA256=coding_checksum)
  # Retain the existing administrator-only terminal authorization.
  config["env"].setdefault("CODING_SHELL_ENABLED",config["env"].get("TERMINAL_ENABLED","false"))
 (ROOT/"secrets.json").write_text(json.dumps(config));(ROOT/"secrets.json").chmod(0o600)
 replace(stage)
 if manifest:shutil.copy2(manifest_path,ROOT/"release.json")
 start();ready();print(json.dumps({"updated":True,"backup":str(backup)}),flush=True)
except Exception:
 stop();replace(backup);shutil.copy2(backup/"secrets.json",ROOT/"secrets.json")
 if (backup/"release.json").exists():shutil.copy2(backup/"release.json",ROOT/"release.json")
 else:(ROOT/"release.json").unlink(missing_ok=True)
 start();print("Rolled back to previous release",flush=True);raise

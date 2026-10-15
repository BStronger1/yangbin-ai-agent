"""Upgrade an existing Turing installation from a trusted local release archive."""
import json,os,secrets,shutil,socket,subprocess,tarfile,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
os.umask(0o077)
python=Path.home()/"apps/agent-workbench/runtime/ai-venv/bin/python"
cache=Path.home()/"apps/agent-workbench/runtime/embeddings"
assert python.is_file() and cache.is_dir(),"Offline embedding runtime/cache missing"
for port in (18127,18128):
    with socket.socket() as s:s.bind(("127.0.0.1",port))
stage=ROOT/("incoming-"+uuid.uuid4().hex[:8]);stage.mkdir(mode=0o700)
with tarfile.open(ROOT/"features.tar.gz") as archive:archive.extractall(stage,filter="data")
assert all((stage/name).is_file() for name in ("app.jar","mcp.jar","deploy/turing/service.py"))
backup=ROOT/"releases"/("before-features-"+uuid.uuid4().hex[:8]);backup.mkdir(parents=True,mode=0o700)
for name in ("app.jar","secrets.json"):
    shutil.copy2(ROOT/name,backup/name)
for name in ("deploy","frontend"):shutil.copytree(ROOT/name,backup/name)
subprocess.run(["tmux","send-keys","-t","yangbin-ai-agent:0.0","C-c"],check=True)
for _ in range(60):
    if subprocess.run(["tmux","has-session","-t","=yangbin-ai-agent"],stderr=subprocess.DEVNULL).returncode:break
    time.sleep(0.5)
else:raise SystemExit("Service did not stop; no files replaced")
try:
    for name in ("app.jar","mcp.jar"):shutil.copy2(stage/name,ROOT/name)
    for name in ("deploy","frontend","knowledge"):shutil.copytree(stage/name,ROOT/name,dirs_exist_ok=True)
    cfg=json.loads((ROOT/"secrets.json").read_text());cfg["embedding_python"]=str(python)
    cfg["env"].setdefault("INTERNAL_API_TOKEN",secrets.token_urlsafe(32))
    cfg["env"].update({"RAG_ENABLED":"true","MCP_ENABLED":"true","TERMINAL_ENABLED":"true",
        "EMBEDDING_BASE_URL":"http://127.0.0.1:18128","EMBEDDING_PORT":"18128","EMBEDDING_API_KEY":"local",
        "EMBEDDING_MODEL":"sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
        "EMBEDDING_CACHE":str(cache),"HF_HUB_OFFLINE":"1","MCP_URL":"http://127.0.0.1:18127",
        "MCP_PORT":"18127","KNOWLEDGE_DIR":str(ROOT/"knowledge")})
    (ROOT/"secrets.json").write_text(json.dumps(cfg,indent=2));(ROOT/"secrets.json").chmod(0o600)
    subprocess.run(["bash",str(ROOT/"deploy/turing/start.sh")],check=True)
    print("Upgrade launched; rollback snapshot:",backup)
except Exception:
    for name in ("app.jar","secrets.json"):shutil.copy2(backup/name,ROOT/name)
    for name in ("deploy","frontend"):shutil.copytree(backup/name,ROOT/name,dirs_exist_ok=True)
    subprocess.run(["bash",str(ROOT/"deploy/turing/start.sh")],check=False)
    raise

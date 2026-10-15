"""Initialize only this application. Credentials stay on the server."""
import argparse,json,os,secrets,socket,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
os.umask(0o077)
p=argparse.ArgumentParser()
p.add_argument("--model-json",type=Path,required=True,help="Authorized existing secrets.json with AI_API_KEY, AI_BASE_URL, AI_MODEL in env")
p.add_argument("--java",type=Path,required=True)
p.add_argument("--bind",default="127.0.0.1")
a=p.parse_args()
if (ROOT/"secrets.json").exists(): raise SystemExit("Already configured; secrets were not changed")
assert a.java.is_file(), "Java runtime is missing"
for host,port in [("127.0.0.1",18126),(a.bind,18825)]:
    with socket.socket() as s:
        s.bind((host,port))
source=json.loads(a.model_json.read_text())["env"]
assert all(source.get(k) for k in ("AI_API_KEY","AI_BASE_URL","AI_MODEL")), "Model configuration incomplete"
for d in ("runtime","logs","run","tmp/pdf"): (ROOT/d).mkdir(parents=True,exist_ok=True)
ROOT.chmod(0o700)
subprocess.run([sys.executable,"-m","venv",str(ROOT/"runtime/venv")],check=True)
subprocess.run([str(ROOT/"runtime/venv/bin/pip"),"install","-r",str(ROOT/"deploy/turing/requirements.txt")],check=True)
env={"MODEL_PROVIDER":"openai","MODEL_API_KEY":source["AI_API_KEY"],"MODEL_BASE_URL":source["AI_BASE_URL"],"MODEL_NAME":source["AI_MODEL"],
     "SPRING_PROFILES_ACTIVE":"prod","SERVER_ADDRESS":"127.0.0.1","SERVER_PORT":"18126","PUBLIC_BIND":a.bind,"PUBLIC_PORT":"18825",
     "INTERNAL_API_TOKEN":secrets.token_urlsafe(32),"ACCESS_USER":"yangbin","ACCESS_PASSWORD":secrets.token_urlsafe(24),"RAG_ENABLED":"false","RAG_CLOUD_ENABLED":"false","EXTENDED_TOOLS_ENABLED":"false","TERMINAL_ENABLED":"false"}
(ROOT/"secrets.json").write_text(json.dumps({"env":env,"java":str(a.java.resolve())},indent=2))
(ROOT/"secrets.json").chmod(0o600)
(ROOT/"access.txt").write_text("Username: yangbin\nPassword: "+env["ACCESS_PASSWORD"]+"\n")
(ROOT/"access.txt").chmod(0o600)
print("Configured. Access credentials are in",ROOT/"access.txt")

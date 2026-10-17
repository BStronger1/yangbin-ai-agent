"""Supervise this application's embeddings, MCP, backend and frontend processes."""
import json,logging,logging.handlers,os,signal,subprocess,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
os.chdir(ROOT)
cfg=json.loads((ROOT/"secrets.json").read_text());env=dict(os.environ,**cfg["env"])
from coding_runtime import prepare as prepare_coding_runtime
prepare_coding_runtime(ROOT,env)
if env.get("CODING_ENABLED")=="true" and env.get("CODING_NODE"):
    env["PATH"]=str(Path(env["CODING_NODE"]).parent)+os.pathsep+env.get("PATH","")
logger=logging.getLogger("service");logger.setLevel(logging.INFO)
logger.addHandler(logging.handlers.RotatingFileHandler(ROOT/"logs/service.log",maxBytes=2_000_000,backupCount=3))
children=[];specs=[];stopping=False

def stop(*args):
    global stopping
    stopping=True
signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
def add(command,process_env,ready=None):
    specs.append((command,process_env,ready))
    child=subprocess.Popen(command,env=process_env,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    children.append(child)
    if ready:
        # Shared NFS cold imports/model loading may take several minutes.
        deadline=time.monotonic()+600
        while not stopping and time.monotonic()<deadline:
            if child.poll() is not None:raise RuntimeError("Dependency exited before readiness: "+ready)
            try:
                with urllib.request.urlopen(ready,timeout=2) as response:
                    if response.status==200:return
            except Exception:pass
            time.sleep(0.5)
        raise RuntimeError("Dependency readiness timeout: "+ready)
try:
    if env.get("RAG_ENABLED")=="true":
        add([cfg["embedding_python"],str(ROOT/"deploy/turing/embedding_server.py")],env,"http://127.0.0.1:18128/health")
    if env.get("MCP_ENABLED")=="true":
        mcp_env=dict(env,SPRING_PROFILES_ACTIVE="sse")
        mcp_env.pop("SERVER_PORT",None)
        add([cfg["java"],"-Xms64m","-Xmx256m","-jar",str(ROOT/"mcp.jar")],mcp_env,"http://127.0.0.1:18127/ready")
    add([cfg["java"],"-Xms128m","-Xmx768m","-jar",str(ROOT/"app.jar")],env,"http://127.0.0.1:18126/api/health")
    add([str(ROOT/"runtime/venv/bin/python"),str(ROOT/"deploy/turing/proxy.py")],env)
    for attempt in range(5):
        while not stopping and all(child.poll() is None for child in children):time.sleep(1)
        if stopping:break
        logger.error("A service exited; restarting failed processes (attempt %d)",attempt+1);time.sleep(5)
        for i,child in enumerate(children):
            if child.poll() is not None:
                command,process_env,_=specs[i]
                children[i]=subprocess.Popen(command,env=process_env,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
except Exception:
    logger.exception("Service startup failed")
finally:
    for child in reversed(children):
        if child.poll() is None:child.terminate()
    for child in children:
        try:child.wait(timeout=20)
        except subprocess.TimeoutExpired:child.kill();child.wait()

"""Supervise only the two processes belonging to this application."""
import json,logging,logging.handlers,os,signal,subprocess,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
os.chdir(ROOT)
cfg=json.loads((ROOT/"secrets.json").read_text())
env=dict(os.environ,**cfg["env"])
logger=logging.getLogger("service")
logger.setLevel(logging.INFO)
logger.addHandler(logging.handlers.RotatingFileHandler(ROOT/"logs/service.log",maxBytes=2_000_000,backupCount=3))
commands=[[cfg["java"],"-Xms128m","-Xmx768m","-jar",str(ROOT/"app.jar")],
          [str(ROOT/"runtime/venv/bin/python"),str(ROOT/"deploy/turing/proxy.py")]]
children=[]
stopping=False
def stop(*args):
    global stopping
    stopping=True
signal.signal(signal.SIGTERM,stop)
signal.signal(signal.SIGINT,stop)
try:
    for command in commands:
        children.append(subprocess.Popen(command,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL))
    for i in range(5):
        while not stopping and all(child.poll() is None for child in children): time.sleep(1)
        if stopping: break
        logger.error("A service exited; restarting failed processes (attempt %d)",i+1)
        time.sleep(5)
        for n,child in enumerate(children):
            if child.poll() is not None:
                children[n]=subprocess.Popen(commands[n],env=env,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
finally:
    for child in children:
        if child.poll() is None: child.terminate()
    for child in children:
        try: child.wait(timeout=20)
        except subprocess.TimeoutExpired: child.kill();child.wait()

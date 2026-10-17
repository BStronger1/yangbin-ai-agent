"""Run the authenticated app on loopback. Build once with --build; stop with Ctrl+C."""
import argparse,getpass,importlib.util,json,os,re,secrets,shutil,signal,socket,subprocess,sys,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def environment():
    env=os.environ.copy();config=ROOT/'.env'
    if config.exists():
        for number,line in enumerate(config.read_text(encoding='utf-8-sig').splitlines(),1):
            line=line.strip()
            if not line or line.startswith('#'):continue
            key,sep,value=line.partition('=');key=key.strip();value=value.strip()
            if not sep or not re.fullmatch('[A-Z][A-Z0-9_]*',key):raise ValueError(f'Invalid .env syntax at line {number}')
            if len(value)>1 and value[0]==value[-1] and value[0] in ('"',"'"):value=value[1:-1]
            env.setdefault(key,value)
    return env

def wait_ready(children,url,seconds,label,origin=None):
    # Loopback startup probes must not inherit a corporate/production HTTP proxy.
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}));deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if any(child.poll() is not None for child in children):raise RuntimeError(label+' exited; inspect logs/local-launcher.log')
        try:
            headers={'Origin':origin} if origin else {}
            with client.open(urllib.request.Request(url,headers=headers),timeout=2) as response:
                if response.status==200:
                    if origin:
                        state=json.load(response)
                        if not isinstance(state,dict) or 'user' not in state:raise ValueError('Unexpected proxy auth response')
                    return
        except (OSError,ValueError):time.sleep(0.3)
    raise RuntimeError(label+' startup timed out; inspect logs/local-launcher.log')

def stop_children(children):
    # Every PID was returned by this invocation's Popen; never stop unrelated services.
    for child in reversed(children):
        if child.poll() is not None:continue
        if os.name=='nt':
            subprocess.run(['taskkill','/PID',str(child.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
        else:
            try:os.killpg(child.pid,signal.SIGTERM)
            except ProcessLookupError:pass
    for child in children:
        try:child.wait(timeout=20)
        except subprocess.TimeoutExpired:
            if os.name!='nt':
                try:os.killpg(child.pid,signal.SIGKILL)
                except ProcessLookupError:pass
            else:child.kill()
            child.wait()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build',action='store_true');parser.add_argument('--check',action='store_true')
    parser.add_argument('--port',type=int,default=18825);parser.add_argument('--backend-port',type=int,default=18126)
    args=parser.parse_args();env=environment()
    java=shutil.which('java',path=env.get('PATH'));node=shutil.which(env.get('CODING_NODE') or 'node',path=env.get('PATH'))
    if not java or not node or not importlib.util.find_spec('aiohttp'):parser.error('Install Java21+, Node22.19+, and pip install -r deploy/turing/requirements.txt')
    version=subprocess.check_output([node,'-p','process.versions.node'],text=True).strip()
    if tuple(map(int,version.split('.')[:2]))<(22,19):parser.error('Node >=22.19 required; current: '+version)
    j=subprocess.run([java,'-version'],capture_output=True,text=True,check=True);match=re.search(r'version "(\d+)',j.stdout+j.stderr)
    if not match or int(match[1])<21:parser.error('Java 21+ required')
    env['PATH']=str(Path(node).parent)+os.pathsep+env.get('PATH','')
    if args.build:
        npm=shutil.which('npm.cmd' if os.name=='nt' else 'npm',path=env.get('PATH'));mvn=shutil.which('mvn.cmd' if os.name=='nt' else 'mvn',path=env.get('PATH'))
        if not npm or not mvn:parser.error('Build requires npm and Maven 3.9+')
        for folder in ('coding-agent','yangbin-ai-agent-frontend'):subprocess.run([npm,'ci','--ignore-scripts'],cwd=ROOT/folder,env=env,check=True)
        subprocess.run([npm,'run','build'],cwd=ROOT/'yangbin-ai-agent-frontend',env=env,check=True)
        subprocess.run([mvn,'-B','-ntp','-DskipTests','package'],cwd=ROOT,env=env,check=True)
    jar=ROOT/'target/yangbin-ai-agent-0.0.1-SNAPSHOT.jar';front=ROOT/'yangbin-ai-agent-frontend/dist'
    entry=Path(env.get('CODING_ENTRY') or str(ROOT/'coding-agent/src/main.mjs')).resolve()
    artifacts={'jar':jar.is_file(),'frontend':(front/'index.html').is_file(),'piDependencies':entry.is_file() and (entry.parent.parent/'node_modules').is_dir()}
    if args.check:print(json.dumps({'node':version,'java':int(match[1]),'artifacts':artifacts}));return
    if not all(artifacts.values()):parser.error('Missing build files; run --build first')
    subprocess.run([node,str(entry),'--check'],env=env,check=True)
    if args.port==args.backend_port:parser.error('Choose different frontend/backend ports')
    for port in (args.port,args.backend_port):
        if not 1024<=port<=65535:parser.error('Ports must be between 1024 and 65535')
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1',port))==0:parser.error(f'Port {port} already in use')
    env.setdefault('ACCESS_USER','admin')
    if not env.get('ACCESS_PASSWORD') and not (ROOT/'tmp/auth/accounts.sqlite').exists():
        password=getpass.getpass('Create local admin password (10-128 characters): ')
        if not 10<=len(password)<=128 or password!=getpass.getpass('Repeat password: '):parser.error('Passwords must match and contain 10-128 characters')
        env['ACCESS_PASSWORD']=password
    if not env.get('ACCESS_PASSWORD'):env['ACCESS_PASSWORD']=secrets.token_urlsafe(32)
    origin=f'http://127.0.0.1:{args.port}'
    env.update(PUBLIC_ORIGIN=origin,SESSION_COOKIE_SECURE='false',PUBLIC_BIND='127.0.0.1',PUBLIC_PORT=str(args.port),SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(args.backend_port),SPRING_PROFILES_ACTIVE='prod',AGENT_FRONTEND_DIR=str(front),CODING_ENABLED='true',CODING_NODE=node,CODING_ENTRY=str(entry),INTERNAL_API_TOKEN=secrets.token_urlsafe(32))
    env.setdefault('MODEL_PROVIDER','dashscope');env.setdefault('CODING_SHELL_ENABLED','false')
    (ROOT/'logs').mkdir(exist_ok=True);children=[]
    with (ROOT/'logs/local-launcher.log').open('ab') as log:
        try:
            options={'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {'start_new_session':True}
            children.append(subprocess.Popen([java,'-jar',str(jar)],cwd=ROOT,env=env,stdout=log,stderr=log,**options))
            wait_ready(children,f'http://127.0.0.1:{args.backend_port}/api/health',180,'Backend')
            children.append(subprocess.Popen([sys.executable,str(ROOT/'deploy/turing/proxy.py')],cwd=ROOT,env=env,stdout=log,stderr=log,**options))
            wait_ready(children,origin+'/api/auth/me',60,'Account proxy',origin)
            print(f'Open {origin}/work and sign in as {env["ACCESS_USER"]}. Ctrl+C stops this instance.',flush=True)
            while all(child.poll() is None for child in children):time.sleep(1)
            raise RuntimeError('A service stopped; inspect logs/local-launcher.log')
        except KeyboardInterrupt:pass
        finally:
            stop_children(children)
if __name__=='__main__':main()

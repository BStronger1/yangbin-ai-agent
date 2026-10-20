"""Invite-only accounts and revocable sessions, stored outside the source tree."""
import asyncio,hashlib,hmac,json,os,re,secrets,sqlite3,time
from collections import defaultdict,deque
from contextlib import contextmanager
from pathlib import Path
from aiohttp import web
COOKIE="yangbin_session"
TTL=24*60*60

def digest(value):return hashlib.sha256(value.encode()).hexdigest()
def password_hash(password):
    salt=secrets.token_hex(16)
    value=hashlib.scrypt(password.encode(),salt=bytes.fromhex(salt),n=131072,r=8,p=1,maxmem=256*1024*1024).hex()
    return "scrypt:131072:8:1:"+salt+":"+value

def password_matches(password,stored):
    try:
        algorithm,n,r,p,salt,expected=stored.split(":")
        if (algorithm,n,r,p)!=("scrypt","131072","8","1"):return False
        actual=hashlib.scrypt(password.encode(),salt=bytes.fromhex(salt),n=131072,r=8,p=1,maxmem=256*1024*1024).hex()
        return hmac.compare_digest(actual,expected)
    except (ValueError,TypeError):return False

def valid_password(value):
    if not isinstance(value,str) or not 10<=len(value)<=128:raise ValueError("密码需为 10-128 个字符")

def valid_username(value):
    if not isinstance(value,str) or not re.fullmatch(r"[A-Za-z0-9_-]{3,32}",value):raise ValueError("用户名需为 3-32 位字母、数字、下划线或短横线")
    return value.lower()

class Accounts:
    def __init__(self,path,admin_name,admin_password):
        self.path=Path(path);self.path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
        with self.db() as db:
            db.executescript("""
            CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,password TEXT NOT NULL,role TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,user_id TEXT NOT NULL,csrf TEXT NOT NULL,expires REAL NOT NULL);
            CREATE INDEX IF NOT EXISTS session_user ON sessions(user_id);
            CREATE TABLE IF NOT EXISTS invites(id TEXT PRIMARY KEY,code TEXT UNIQUE NOT NULL,uses INTEGER NOT NULL DEFAULT 0,max_uses INTEGER NOT NULL,expires REAL NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,created REAL NOT NULL);
            """)
            if not db.execute("SELECT 1 FROM users WHERE id='admin'").fetchone():
                valid_password(admin_password)
                db.execute("INSERT INTO users VALUES(?,?,?,?,1,?)",("admin",valid_username(admin_name),password_hash(admin_password),"admin",time.time()))
        self.path.chmod(0o600)
        self.dummy=password_hash(secrets.token_urlsafe(24))
    @contextmanager
    def db(self):
        db=sqlite3.connect(self.path,timeout=15);db.row_factory=sqlite3.Row
        try:
            with db:yield db
        finally:db.close()
    @staticmethod
    def public(row):return {k:row[k] for k in ("id","username","role","active")}
    def login(self,name,password):
        name=valid_username(name)
        if not isinstance(password,str) or len(password)>128:raise ValueError("用户名或密码错误")
        with self.db() as db:row=db.execute("SELECT * FROM users WHERE username=?",(name,)).fetchone()
        matched=password_matches(password,row['password'] if row else self.dummy)
        if not row or not matched or not row['active']:raise ValueError("用户名或密码错误，或账号已停用")
        return self.new_session(row['id'],row['password'])
    def new_session(self,user_id,expected_password=None):
        token=secrets.token_urlsafe(32);csrf=secrets.token_urlsafe(32);now=time.time()
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            row=db.execute("SELECT * FROM users WHERE id=?",(user_id,)).fetchone()
            if not row or not row['active'] or (expected_password and row['password']!=expected_password):raise ValueError("账号状态已变化，请重新登录")
            db.execute("DELETE FROM sessions WHERE expires<?",(now,))
            db.execute("INSERT INTO sessions VALUES(?,?,?,?)",(digest(token),user_id,csrf,now+TTL))
        return token,{**self.public(row),"csrf":csrf}
    def session(self,token):
        if not token or len(token)>100:return None
        with self.db() as db:row=db.execute("SELECT users.*,sessions.csrf FROM sessions JOIN users ON users.id=sessions.user_id WHERE token=? AND expires>? AND active=1",(digest(token),time.time())).fetchone()
        return None if row is None else {**self.public(row),"csrf":row['csrf']}
    def logout(self,token):
        with self.db() as db:db.execute("DELETE FROM sessions WHERE token=?",(digest(token),))
    def register(self,name,password,code):
        name=valid_username(name);valid_password(password)
        if not isinstance(code,str) or not 10<=len(code)<=100:raise ValueError("邀请码无效或已过期")
        hashed=password_hash(password);now=time.time();user_id=secrets.token_hex(16)
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            invite=db.execute("SELECT * FROM invites WHERE code=? AND revoked=0 AND expires>? AND uses<max_uses",(digest(code.strip()),now)).fetchone()
            if not invite:raise ValueError("邀请码无效或已过期")
            try:db.execute("INSERT INTO users VALUES(?,?,?,?,1,?)",(user_id,name,hashed,"user",now))
            except sqlite3.IntegrityError:raise ValueError("用户名已被使用")
            db.execute("UPDATE invites SET uses=uses+1 WHERE id=?",(invite['id'],))
        return self.new_session(user_id)
    def create_invite(self,max_uses,days):
        if type(max_uses)!=int or not 1<=max_uses<=20 or type(days)!=int or not 1<=days<=30:raise ValueError("使用次数为 1-20，有效期为 1-30 天")
        code=secrets.token_urlsafe(24);id=secrets.token_hex(12);now=time.time()
        with self.db() as db:db.execute("INSERT INTO invites VALUES(?,?,0,?,?,0,?)",(id,digest(code),max_uses,now+days*86400,now))
        return {"id":id,"code":code,"maxUses":max_uses,"expires":now+days*86400}
    def invites(self):
        with self.db() as db:return [dict(row) for row in db.execute("SELECT id,uses,max_uses,expires,revoked,created FROM invites ORDER BY created DESC LIMIT 100")]
    def revoke(self,id):
        with self.db() as db:db.execute("UPDATE invites SET revoked=1 WHERE id=?",(id,))
    def users(self):
        with self.db() as db:return [self.public(row) for row in db.execute("SELECT * FROM users ORDER BY created")]
    def set_active(self,id,active):
        if id=='admin' or type(active)!=bool:raise ValueError("不能停用管理员，状态必须是布尔值")
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            if not db.execute("UPDATE users SET active=? WHERE id=?",(int(active),id)).rowcount:raise ValueError("账号不存在")
            db.execute("DELETE FROM sessions WHERE user_id=?",(id,))
    def change_password(self,id,old,new):
        valid_password(new)
        with self.db() as db:row=db.execute("SELECT password FROM users WHERE id=?",(id,)).fetchone()
        if not isinstance(old,str) or len(old)>128 or not row or not password_matches(old,row['password']):raise ValueError("当前密码不正确")
        hashed=password_hash(new)
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            changed=db.execute("UPDATE users SET password=? WHERE id=? AND password=?",(hashed,id,row['password'])).rowcount
            if not changed:raise ValueError("密码已变化，请重新登录")
            db.execute("DELETE FROM sessions WHERE user_id=?",(id,))

class RateLimit:
    def __init__(self):self.events=defaultdict(deque)
    def check(self,key,limit=12,window=300):
        now=time.monotonic();events=self.events[key]
        while events and events[0]<now-window:events.popleft()
        if len(events)>=limit:raise web.HTTPTooManyRequests(text=json.dumps({"message":"尝试过于频繁，请稍后重试"}),content_type="application/json")
        events.append(now)
        if len(self.events)>10000:
            self.events=defaultdict(deque,{k:v for k,v in self.events.items() if v and v[-1]>now-900})

def session_response(token,user):
    response=web.json_response({"user":user},headers={"Cache-Control":"no-store"})
    response.set_cookie(COOKIE,token,max_age=TTL,httponly=True,samesite="Strict",secure=os.environ.get("SESSION_COOKIE_SECURE")=="true",path="/")
    return response

async def read_body(request):
    raw=await request.read()
    if len(raw)>4096:raise ValueError("请求过大")
    body=json.loads(raw)
    if not isinstance(body,dict):raise ValueError("请求格式无效")
    return body

async def auth_api(request):
    action=request.match_info['action'];accounts=request.app['accounts'];user=request.get('user')
    try:
        if action=='me' and request.method=='GET':return web.json_response({"user":user})
        if action in ('login','register') and request.method=='POST':
            if request.content_length and request.content_length>4096:raise ValueError("请求过大")
            body=await read_body(request)
            if not isinstance(body,dict):raise ValueError("请求格式无效")
            request.app['rate_limit'].check('ip:'+str(request.remote),limit=20)
            request.app['rate_limit'].check('user:'+str(body.get('username','')).lower(),limit=10)
            if request.app['password_slots'].locked():
                raise web.HTTPServiceUnavailable(text=json.dumps({'message':'身份验证繁忙，请稍后重试'}),content_type='application/json',headers={'Retry-After':'2'})
            async with request.app['password_slots']:
                if action=='login':token,identity=await asyncio.to_thread(accounts.login,body.get('username'),body.get('password'))
                else:token,identity=await asyncio.to_thread(accounts.register,body.get('username'),body.get('password'),body.get('invite'))
            return session_response(token,identity)
        if not user:raise web.HTTPUnauthorized()
        if action=='logout' and request.method=='POST':
            accounts.logout(request.cookies.get(COOKIE,''));response=web.json_response({"ok":True});response.del_cookie(COOKIE,path="/");return response
        if action=='password' and request.method=='POST':
            body=await read_body(request);request.app['rate_limit'].check('password:'+user['id'],limit=5)
            async with request.app['password_slots']:await asyncio.to_thread(accounts.change_password,user['id'],body.get('oldPassword'),body.get('newPassword'))
            response=web.json_response({"ok":True});response.del_cookie(COOKIE,path="/");return response
        if user['role']!='admin':raise web.HTTPForbidden()
        if action=='invites' and request.method=='GET':return web.json_response(accounts.invites())
        if action=='invites' and request.method=='POST':
            body=await read_body(request);return web.json_response(accounts.create_invite(body.get('maxUses',1),body.get('days',7)))
        if action.startswith('invites/') and request.method=='DELETE':accounts.revoke(action[8:]);return web.json_response({"ok":True})
        if action=='users' and request.method=='GET':return web.json_response(accounts.users())
        if action.startswith('users/') and request.method=='POST':
            body=await read_body(request);accounts.set_active(action[6:],body.get('active'));return web.json_response({"ok":True})
        raise web.HTTPNotFound()
    except (ValueError,TypeError,AttributeError):
        # Validation errors never contain password values or database internals.
        import sys
        error=sys.exc_info()[1]
        message=str(error) if isinstance(error,ValueError) else "请求格式无效"
        return web.json_response({"message":message},status=400)

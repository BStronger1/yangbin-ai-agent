"""One-shot public browser acceptance. Never retries or replays model submissions.
Credentials stay in memory; requires account-specific hard governance limits.
Run on server with --execute, --root, --browser and a fresh --evidence directory.
"""
import argparse, hashlib, json, os, re, secrets, sqlite3, time, uuid
from pathlib import Path
from urllib.parse import urlencode, urlsplit

class CheckFailure(RuntimeError): pass
def require(ok, name):
    if not ok: raise CheckFailure(name)
def atomic_json(path, data):
    path=Path(path); tmp=path.with_name(path.name+'.new')
    with open(tmp,'w',encoding='utf-8') as f:
        os.chmod(tmp,0o600); json.dump(data,f,ensure_ascii=False,indent=2); f.flush(); os.fsync(f.fileno())
    os.replace(tmp,path)
def reserve_run(folder):
    folder=Path(folder); folder.mkdir(parents=True,exist_ok=True,mode=0o700)
    with open(folder/'STARTED.json','x',encoding='utf-8') as f:
        os.chmod(folder/'STARTED.json',0o600)
        json.dump({'startedAt':time.time(),'policy':'never replay'},f); f.flush(); os.fsync(f.fileno())
def install_test_limits(root,owner,created):
    require(owner in created and re.fullmatch('[a-f0-9]{32}',owner),'limits_owner_not_new')
    root=Path(root).resolve(); directory=root/'tmp'/'governance'/owner
    require(not directory.is_symlink(),'limits_symlink')
    directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    require(directory.resolve().is_relative_to(root),'limits_escape')
    target=directory/'limits.json'
    require(not target.exists() and not target.is_symlink(),'limits_already_exist')
    atomic_json(target,{'accountConcurrency':1,'dailyRequests':8,'dailyTokens':200000,'outputTokens':512})
def operator_accounts(root):
    """Provision invite only after read-only proof of an existing admin.

    Empty bootstrap arguments deliberately fail closed if admin disappears before
    Accounts opens the database; this path must never create/reset an admin.
    """
    path=Path(root).resolve()/'tmp'/'auth'/'accounts.sqlite'
    require(path.is_file() and not path.is_symlink(),'operator_existing_auth_required')
    connection=sqlite3.connect(path.as_uri()+'?mode=ro',uri=True)
    try:
        row=connection.execute("SELECT id,role FROM users WHERE id='admin'").fetchone()
        require(row==('admin','admin'),'operator_existing_admin_required')
    finally:connection.close()
    from accounts import Accounts
    return Accounts(path,'','')

def usage_difference(before,after):
    require(before['day']==after['day'],'utc_day_changed_no_replay')
    result={k:after[k]-before[k] for k in ('requests','knownTokens','unknownUsageRequests','budgetChargedTokens')}
    require(all(v>=0 for v in result.values()),'negative_ledger_delta'); result['costStatus']='UNKNOWN'; return result
def exact_write_approval(e,path,content):
    return e.get('type')=='approval_required' and e.get('toolName')=='write' and e.get('arguments')=={'path':path,'content':content}

FETCH="""async ({path,method,data,multipart})=>{
const me=await fetch('/api/auth/me',{cache:'no-store',signal:AbortSignal.timeout(15000)});
const user=(await me.json()).user, headers=user?{'X-CSRF-Token':user.csrf}:{};let body;
if(multipart){body=new FormData();body.append('file',new Blob([multipart.content],{type:'text/plain'}),multipart.name);}
else if(data!==null){headers['Content-Type']='application/json';body=JSON.stringify(data);}
const r=await fetch(path,{method,headers,body,cache:'no-store',signal:AbortSignal.timeout(30000)});
const text=await r.text();let result=text;
if((r.headers.get('content-type')||'').includes('json')){try{result=JSON.parse(text);}catch{}}
return {status:r.status,data:result,contentType:r.headers.get('content-type'),disposition:r.headers.get('content-disposition')};} """

class Acceptance:
    def __init__(self,args):
        self.args=args;self.root=Path(args.root).resolve();self.out=Path(args.evidence).resolve()
        self.report={'version':'M025','checks':[],'logicalTasks':[],'createdOwners':[],'cleanup':[],
          'limitations':['Actual public SSE transport, not token-level streaming.','Cancel observes a pending write and 5-second file stability.','History recovery is browser reload, not service restart.','Cost UNKNOWN; quota reservations differ from invoiced tokens.']}
        self.admin=self.a=self.b=self.invite_id=self.before=None;self.active=[];self.ops_accounts=None
    def save(self): atomic_json(self.out/'report.json',self.report)
    def check(self,name,ok,**evidence):
        self.report['checks'].append({'name':name,'passed':bool(ok),**evidence});self.save();require(ok,name)
    def request(self,page,path,method='GET',data=None,statuses=(200,),multipart=None):
        r=page.evaluate(FETCH,{'path':path,'method':method,'data':data,'multipart':multipart})
        require(r['status'] in statuses,'http_%s_%s_%s'%(method,path.split('?')[0],r['status']));return r
    def api(self,page,path,method='GET',data=None,statuses=(200,)):
        return self.request(page,path,method,data,statuses)['data']
    def new_page(self,browser):
        c=browser.new_context(viewport={'width':1360,'height':900},locale='zh-CN',timezone_id='Asia/Singapore')
        p=c.new_page();p.set_default_timeout(20000);p.goto(self.args.base+'/login',wait_until='domcontentloaded',timeout=45000);return p
    def login(self,p,name,password):
        p.get_by_label('用户名',exact=True).fill(name);p.get_by_label('密码',exact=True).fill(password)
        p.get_by_role('button',name='登录',exact=True).click();p.get_by_role('button',name='退出',exact=True).wait_for(timeout=30000)
        return self.api(p,'/api/auth/me')['user']
    def accounts(self,browser):
        if getattr(self.args,'operator_provision',False):
            self.ops_accounts=operator_accounts(self.root)
            invite=self.ops_accounts.create_invite(2,1)
            self.report['provisioning']='ssh_operator_generated_invite'
        else:
            env=json.loads((self.root/'secrets.json').read_text())['env']
            self.admin=self.new_page(browser);self.login(self.admin,env['ACCESS_USER'],env['ACCESS_PASSWORD']);del env
            invite=self.api(self.admin,'/api/auth/invites','POST',{'maxUses':2,'days':1})
            self.report['provisioning']='normal_admin_browser'
        self.invite_id=invite['id'];self.report['inviteId']=self.invite_id;self.save()
        for attr in ('a','b'):
            p=self.new_page(browser);name='m025_'+uuid.uuid4().hex[:16];password=secrets.token_urlsafe(24)
            registered=self.api(p,'/api/auth/register','POST',{'username':name,'password':password,'invite':invite['code']})
            owner=registered['user']['id'];self.report['createdOwners'].append(owner);self.save()
            self.api(p,'/api/auth/logout','POST');p.goto(self.args.base+'/login',wait_until='domcontentloaded')
            self.login(p,name,password);setattr(self,attr,p)
        self.check('two_independent_browser_logins',len(self.report['createdOwners'])==2)
        install_test_limits(self.root,self.report['createdOwners'][0],self.report['createdOwners'])
        self.before=self.api(self.a,'/api/ai/governance');self.report['ledgerBefore']=self.before;l=self.before['limits']
        self.check('hard_account_budget',l['dailyRequests']<=8 and l['outputTokens']<=512 and l['accountConcurrency']==1 and self.before['requests']==0,limits=l)
    def free_checks(self):
        content='M025 synthetic public file round trip\nvalue=7391\n'
        r=self.request(self.a,'/api/ai/work/partners/office/files','POST',multipart={'name':'m025-synthetic.txt','content':content})['data']
        path='/api/ai/work/partners/office/resources/'+r['id']+'/content';self.report['resourceId']=r['id']
        downloaded=self.request(self.a,path)
        self.check('upload_download_identical',downloaded['data']==content and 'attachment' in (downloaded['disposition'] or ''),sha256=hashlib.sha256(content.encode()).hexdigest())
        self.request(self.b,path,statuses=(400,403,404));self.check('resource_account_isolation',True)
        project=self.api(self.a,'/api/ai/coding/projects','POST',{'name':'M025 synthetic acceptance'})
        self.project=project['id'];self.report['projectId']=self.project;prefix='/api/ai/coding/projects/'+self.project
        self.api(self.a,prefix+'/file','PUT',{'path':'baseline.txt','content':'before\n'})
        self.check('coding_file_roundtrip',self.api(self.a,prefix+'/file?path=baseline.txt')['content']=='before\n')
        self.request(self.b,prefix+'/files',statuses=(400,403,404));self.check('coding_project_account_isolation',True)
        self.request(self.a,prefix+'/file','PUT',{'path':'../escape.txt','content':'blocked'},statuses=(400,403))
        self.check('coding_path_traversal_rejected',True)
    def task(self,kind):
        require(len(self.report['logicalTasks'])<3,'logical_task_limit');current=self.api(self.a,'/api/ai/governance')
        require(current['day']==self.before['day'],'utc_day_changed_no_replay');require(current['requests']<8,'request_limit')
        item={'kind':kind,'requestId':str(uuid.uuid4()),'state':'STARTED'};self.report['logicalTasks'].append(item);self.save();return item
    def chat(self):
        task=self.task('manus_public_sse');cid=str(uuid.uuid4());task['chatId']=cid;self.save()
        cancel='/api/ai/manus/conversations/'+cid+'/cancel';self.active.append(cancel)
        path='/api/ai/manus/chat?'+urlencode({'chatId':cid,'requestId':task['requestId'],'message':'这是合成验收。不要调用任何工具。仅回复 M025_PUBLIC_OK，然后结束。'})
        result=self.a.evaluate("""async path=>{
          const start=performance.now(),r=await fetch(path,{signal:AbortSignal.timeout(90000),cache:'no-store'});
          const reader=r.body.getReader(),decoder=new TextDecoder();let text='',chunks=0,firstMs=null,lastMs=null;
          while(true){const {done,value}=await reader.read();if(done)break;if(firstMs===null)firstMs=performance.now()-start;
          lastMs=performance.now()-start;chunks++;text+=decoder.decode(value,{stream:true});
          if(text.length>128000){await reader.cancel();throw Error('SSE bound');}}
          return {status:r.status,contentType:r.headers.get('content-type'),text,chunks,firstMs,lastMs};} """,path)
        self.check('public_sse_delivery',result['status']==200 and 'text/event-stream' in (result['contentType'] or '') and 'M025_PUBLIC_OK' in result['text'] and '[DONE]' in result['text'] and 'event:failure' not in result['text'].replace(' ',''),chunks=result['chunks'],firstMs=result['firstMs'],lastMs=result['lastMs'])
        history='/api/ai/manus/conversations/'+cid;original=self.api(self.a,history);self.a.reload(wait_until='domcontentloaded');recovered=self.api(self.a,history)
        self.check('manus_history_reload',original==recovered and recovered['turns'][-1]['status']=='SUCCEEDED')
        self.request(self.b,history,statuses=(400,403,404));self.check('manus_account_isolation',True)
        task['state']='SUCCEEDED';self.active.remove(cancel);self.save()
    def coding(self,cancelled=False):
        kind='coding_cancel_pending_write' if cancelled else 'coding_approved_write';task=self.task(kind)
        filename='must-not-exist.txt' if cancelled else 'accepted.txt';content='M025_CANCEL_MUST_NOT_WRITE\n' if cancelled else 'M025_APPROVED_WRITE\n'
        session=self.api(self.a,'/api/ai/coding/sessions','POST',{'projectId':self.project,'title':kind,'approvalMode':'ask'})
        prefix='/api/ai/coding/sessions/'+session['id'];task['sessionId']=session['id'];self.save();cancel=prefix+'/cancel';self.active.append(cancel)
        prompt='Synthetic acceptance. Call the write tool exactly once with path '+json.dumps(filename)+' and content '+json.dumps(content)+'. No read/list/edit/bash or other tools. After the write, reply DONE and stop.'
        self.api(self.a,prefix+'/turns','POST',{'requestId':task['requestId'],'revision':session['revision'],'prompt':prompt,'approvalMode':'ask'},statuses=(202,))
        deadline=time.monotonic()+90;approved=False
        while time.monotonic()<deadline:
            session=self.api(self.a,prefix)
            if session['status']=='WAITING_APPROVAL':
                pending=[e for e in session['events'] if e.get('type')=='approval_required' and not any(r.get('type')=='approval_resolved' and r.get('approvalId')==e.get('approvalId') for r in session['events'])]
                require(len(pending)==1,'unexpected_approval_count');event=pending[0]
                require(not approved and exact_write_approval(event,filename,content),'unexpected_tool_never_auto_approve')
                files=self.api(self.a,'/api/ai/coding/projects/'+self.project+'/files')
                self.check(kind+'_not_written_before_approval',filename not in [f['path'] for f in files])
                if cancelled:
                    self.api(self.a,cancel,'POST');self.request(self.a,prefix+'/approvals/'+event['approvalId'],'POST',{'approved':True},statuses=(400,409))
                    self.check('stale_approval_after_cancel_rejected',True);break
                self.api(self.a,prefix+'/approvals/'+event['approvalId'],'POST',{'approved':True});approved=True
            elif session['status'] not in ('RUNNING','WAITING_APPROVAL'): break
            time.sleep(1)
        else: raise CheckFailure(kind+'_timeout_no_retry')
        if cancelled:
            snapshots=[]
            for _ in range(6):
                files=self.api(self.a,'/api/ai/coding/projects/'+self.project+'/files');snapshots.append(files)
                require(filename not in [f['path'] for f in files],'cancelled_file_written');time.sleep(1)
            session=self.api(self.a,prefix)
            self.check('cancelled_write_stable',session['status']=='CANCELLED' and all(s==snapshots[0] for s in snapshots),observedSeconds=5)
        else:
            self.check('coding_real_task_success',approved and session['status']=='SUCCEEDED');diff=self.api(self.a,prefix+'/diff')
            self.check('coding_exact_diff',len(diff)==1 and diff[0]['path']==filename and diff[0]['after']==content and diff[0]['kind']=='added')
            self.a.reload(wait_until='domcontentloaded');self.check('coding_history_reload',self.api(self.a,prefix)==session)
        self.request(self.b,prefix,statuses=(400,403,404));self.check(kind+'_account_isolation',True)
        task['state']='SUCCEEDED';self.active.remove(cancel);self.save()
    def cleanup(self):
        for path in self.active:
            try:self.api(self.a,path,'POST');self.report['cleanup'].append({'action':'cancel','ok':True})
            except Exception:self.report['cleanup'].append({'action':'cancel','ok':False})
        if self.a is not None and self.before is not None:
            try:
                deadline=time.monotonic()+30
                while True:
                    after=self.api(self.a,'/api/ai/governance')
                    if after['active']==0 or time.monotonic()>=deadline:break
                    time.sleep(1)
                self.report['ledgerAfter']=after;self.report['usageDelta']=usage_difference(self.before,after)
                self.report['modelEvents']=self.api(self.a,'/api/ai/governance/events')
                self.check('ledger_settled_within_budget',after['active']==0 and self.report['usageDelta']['requests']<=8)
            except Exception as error:self.report['cleanup'].append({'action':'ledger','ok':False,'errorType':type(error).__name__})
        if self.admin is not None or self.ops_accounts is not None:
            for owner in self.report['createdOwners']:
                try:
                    require(re.fullmatch('[a-f0-9]{32}',owner),'cleanup_generated_owner_required')
                    if self.ops_accounts is not None:self.ops_accounts.set_active(owner,False)
                    else:self.api(self.admin,'/api/auth/users/'+owner,'POST',{'active':False})
                    self.report['cleanup'].append({'action':'disable_generated_owner','owner':owner,'ok':True})
                except Exception:self.report['cleanup'].append({'action':'disable_generated_owner','owner':owner,'ok':False})
            if self.invite_id:
                try:
                    if self.ops_accounts is not None:self.ops_accounts.revoke(self.invite_id)
                    else:self.api(self.admin,'/api/auth/invites/'+self.invite_id,'DELETE')
                    self.report['cleanup'].append({'action':'revoke_generated_invite','ok':True})
                except Exception:self.report['cleanup'].append({'action':'revoke_generated_invite','ok':False})
        self.save()
    def run(self):
        # Do not overwrite an earlier run's evidence when the exclusive marker fails.
        reserve_run(self.out);self.report['status']='RUNNING';self.save()
        try:
            from playwright.sync_api import sync_playwright
            with sync_playwright() as p:
                browser=None
                try:
                    launch={'executable_path':self.args.browser,'headless':True,'chromium_sandbox':True,'args':[]}
                    if self.args.dns_address:
                        import ipaddress
                        ipaddress.IPv4Address(self.args.dns_address);host=urlsplit(self.args.base).hostname
                        launch['args'].append('--host-resolver-rules=MAP '+host+' '+self.args.dns_address)
                        self.report['dnsOverride']={'host':host,'address':self.args.dns_address,'source':'operator freshly verified public DNS'}
                    browser=p.chromium.launch(**launch)
                    self.accounts(browser);self.free_checks();self.chat();self.coding();self.coding(cancelled=True)
                    self.report['status']='PASSED'
                finally:
                    # Cleanup also runs on account setup failure or keyboard interrupt.
                    try:self.cleanup()
                    finally:
                        if browser is not None:browser.close()
        except BaseException as error:
            # Never save Playwright exception text: it can contain login arguments.
            self.report['status']='FAILED_NO_REPLAY'
            self.report['failure']=str(error) if isinstance(error,CheckFailure) else type(error).__name__
        finally:
            if any(not c['passed'] for c in self.report['checks']) or any(not c['ok'] for c in self.report['cleanup']):
                self.report['status']='FAILED_NO_REPLAY'
            self.save()
        print(json.dumps({'status':self.report.get('status'),'evidence':str(self.out/'report.json'),'usageDelta':self.report.get('usageDelta')}))
        return 0 if self.report.get('status')=='PASSED' else 1

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',required=True);p.add_argument('--browser',required=True);p.add_argument('--evidence',required=True)
    p.add_argument('--base',default='https://www.yangbinai.homes');p.add_argument('--dns-address',help='Only freshly verified public IPv4; TLS still verified');p.add_argument('--execute',action='store_true')
    p.add_argument('--operator-provision',action='store_true',help='SSH operator creates only temporary invite and disables this run users; never reads secrets or changes admin')
    args=p.parse_args();require(args.execute,'explicit_execute_required');parsed=urlsplit(args.base)
    require(parsed.scheme=='https' and parsed.hostname and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment and parsed.path in ('','/'),'https_origin_required')
    args.base=args.base.rstrip('/');return Acceptance(args).run()
if __name__=='__main__':raise SystemExit(main())

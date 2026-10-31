import {app,BrowserWindow,dialog,ipcMain,shell,Menu} from 'electron';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {nativeDirectory} from './native-directory.mjs';
import {LocalProcessManager} from '../coding-agent/src/local-runner.mjs';

const here=path.dirname(fileURLToPath(import.meta.url));
let origin='https://www.yangbinai.homes';
if(!app.isPackaged && process.env.YANGBIN_DEV_ORIGIN){
  const candidate=new URL(process.env.YANGBIN_DEV_ORIGIN);
  if(candidate.protocol!=='http:' || !['127.0.0.1','localhost'].includes(candidate.hostname))throw new Error('Development origin must be loopback');
  origin=candidate.origin;
}
if(!app.isPackaged && process.env.YANGBIN_DEV_DATA)app.setPath('userData',process.env.YANGBIN_DEV_DATA);
const spaces=new Map(),bindings=new Map();let window,quitting=false;
const stateFile=()=>path.join(app.getPath('userData'),'project-directories.json');
const key=(owner,project)=>JSON.stringify([origin,owner,project]);
function validText(s){return typeof s==='string' && s.length>0 && s.length<=160 && !/[\x00-\x1f]/.test(s);}
function validateSender(event){
  if(!window || event.sender!==window.webContents || event.senderFrame!==window.webContents.mainFrame || new URL(event.senderFrame.url).origin!==origin)throw new Error('Untrusted desktop request');
}
async function owner(){
  const response=await window.webContents.session.fetch(origin+'/api/auth/me',{credentials:'include',redirect:'error',cache:'no-store'});
  if(!response.ok)throw new Error('请先登录');
  const data=await response.json(),id=data.user?.id || data.user?.username;
  if(!validText(id))throw new Error('登录状态已失效'); return id;
}
function descriptor(space){return {desktop:true,id:space.id,name:path.basename(space.path),path:space.path,platform:process.platform};}
async function openSpace(directory,account){
  const real=await fs.realpath(directory);
  const existing=[...spaces.values()].find(s=>s.path===real && s.owner===account);
  if(existing)return descriptor(existing);
  if(spaces.size>=16)throw new Error('已打开项目过多，请重启客户端后继续');
  const files=await nativeDirectory(real),processes=await new LocalProcessManager(real).init();
  const space={id:randomUUID(),path:real,owner:account,files,processes,receipts:new Map(),queue:Promise.resolve()};
  spaces.set(space.id,space);return descriptor(space);
}
async function persist(){
  const temp=stateFile()+'.tmp';await fs.writeFile(temp,JSON.stringify(Object.fromEntries(bindings)),{mode:0o600});await fs.rename(temp,stateFile());
}
function handle(name,action){ipcMain.handle('desktop:'+name,async(event,args)=>{validateSender(event);const account=await owner();validateSender(event);return action(args,account);});}
handle('choose',async(_,account)=>{
  const result=await dialog.showOpenDialog(window,{title:'选择本机项目文件夹',properties:['openDirectory','createDirectory']});
  if(result.canceled)return null;
  return openSpace(result.filePaths[0],account);
});
handle('bind',async({owner:account,project,id},actual)=>{
  const space=spaces.get(id);if(account!==actual || !validText(project) || !space || space.owner!==actual)throw new Error('项目绑定无效');
  bindings.set(key(actual,project),space.path);await persist();return true;
});
handle('restore',async({owner:account,project},actual)=>{
  if(account!==actual || !validText(project))throw new Error('项目身份无效');
  const directory=bindings.get(key(actual,project));return directory?openSpace(directory,actual):null;
});
handle('editor',async({id},actual)=>{
  const space=spaces.get(id);if(!space || space.owner!==actual)throw new Error('请选择项目目录');
  const url='vscode://file/'+space.path.replace(/\\/g,'/').split('/').map(encodeURIComponent).join('/');
  await shell.openExternal(url);return true;
});
handle('authorize-task',async({id,sessionId,turnId},actual)=>{
  const space=spaces.get(id);
  if(!space || space.owner!==actual || !validText(sessionId) || !validText(turnId))throw new Error('本轮授权参数无效');
  if(space.automaticTask?.sessionId===sessionId && space.automaticTask?.turnId===turnId && space.automaticTask.expiresAt>Date.now())return true;
  const response=await window.webContents.session.fetch(origin+'/api/ai/coding/sessions/'+encodeURIComponent(sessionId),{credentials:'include',redirect:'error',cache:'no-store'});
  if(!response.ok)throw new Error('任务身份无法核验');
  const task=await response.json();
  if(bindings.get(key(actual,task.projectId))!==space.path || task.turns?.some(t=>t.id===turnId))throw new Error('只能为尚未提交的新一轮任务授权');
  const answer=await dialog.showMessageBox(window,{type:'question',title:'本轮全部允许',message:'允许本轮任务自动修改文件并运行命令？',detail:`项目：${space.path}\n本轮不再逐次确认。命令以你的电脑账号执行，可能访问项目目录以外的资源。任务完成、取消或超时后失效。`,buttons:['取消','允许本轮自动执行'],defaultId:0,cancelId:0,noLink:true});
  if(answer.response!==1)throw new Error('用户取消了本轮自动执行授权');
  if(await owner()!==actual)throw new Error('登录账号已切换，授权已取消');
  space.automaticTask={sessionId,turnId,expiresAt:Date.now()+20*60*1000};return true;
});
handle('execute',async({id,operation,args={},requestId,context},actual)=>{
  const space=spaces.get(id);if(!space || space.owner!==actual)throw new Error('项目目录未连接');
  if(!['ping','begin_turn','files','list','read','write','edit','start','preview','process_status','stop'].includes(operation))throw new Error('不支持的本机操作');
  if(!args || typeof args!=='object' || JSON.stringify(args).length>160000 || !/^[a-f0-9-]{36}$/.test(requestId))throw new Error('无效本机请求');
  const signature=JSON.stringify({operation,args,context});
  if(space.receipts.has(requestId)){
    const prior=space.receipts.get(requestId);if(prior.signature!==signature)throw new Error('请求编号已用于其他操作');return prior.result;
  }
  if(space.receipts.size>=10000)throw new Error('本机操作记录已满，请结束任务并重启客户端');
  const checkTask=async()=>{
    if(!context)return false;
    if(!validText(context.sessionId)||!validText(context.turnId)||!Number.isFinite(context.expiresAt)||context.expiresAt<=Date.now())throw new Error('任务操作已过期');
    const response=await window.webContents.session.fetch(origin+'/api/ai/coding/sessions/'+encodeURIComponent(context.sessionId),{credentials:'include',redirect:'error',cache:'no-store'});
    if(!response.ok)throw new Error('任务状态无法核验');
    const task=await response.json();
    if(bindings.get(key(actual,task.projectId))!==space.path)throw new Error('任务与本机项目目录不匹配');
    if(!['RUNNING','QUEUED','WAITING_APPROVAL','AWAITING_APPROVAL'].includes(task.status)||task.turns?.at(-1)?.id!==context.turnId||context.expiresAt<=Date.now()){space.automaticTask=null;throw new Error('任务已停止，本机操作已取消');}
    const grant=space.automaticTask;
    return task.turns.at(-1).approvalMode==='allow' && grant?.sessionId===context.sessionId && grant?.turnId===context.turnId && grant.expiresAt>Date.now();
  };
  const run=async()=>{
    if(await owner()!==actual)throw new Error('登录账号已切换，操作已取消');
    const automatic=await checkTask();
    if(operation==='begin_turn'){space.automaticTask=null;await space.files.beginTurn();return {output:'',changes:[]};}
    // A native confirmation protects process execution even if remote UI code
    // is compromised. File access remains scoped to the selected directory.
    if(['start','preview','stop'].includes(operation) && !automatic){
      const detail=operation==='start'?String(args.command||''):operation==='preview'?`预览 ${args.path||'index.html'}`:`停止进程 ${args.id||''}`;
      const answer=await dialog.showMessageBox(window,{type:'question',title:'确认本机操作',message:detail,detail:`项目：${space.path}\n命令将以你的电脑账号运行。`,buttons:['取消','允许这次操作'],defaultId:0,cancelId:0,noLink:true});
      if(answer.response!==1)throw new Error('用户取消了本机操作');
      if(await owner()!==actual)throw new Error('登录账号已切换，操作已取消');
      await checkTask();
    }
    if(['start','preview','process_status','stop'].includes(operation))return {output:JSON.stringify(await space.processes.execute(operation,args))};
    if(operation==='files')return {files:await space.files.files(),changes:[...space.files.changes.values()]};
    const output=operation==='ping'?JSON.stringify({localProcesses:true,platform:process.platform}):operation==='read'?await space.files.read(args.path,{observe:args.observe!==false}):await space.files.execute(operation,args);
    return {output,changes:[...space.files.changes.values()]};
  };
  const result=space.queue.catch(()=>{}).then(run);space.queue=result;
  space.receipts.set(requestId,{signature,result});return result;
});

app.whenReady().then(async()=>{
try{for(const [k,v]of Object.entries(JSON.parse(await fs.readFile(stateFile(),'utf8'))))if(typeof v==='string')bindings.set(k,v);}catch(e){if(e.code!=='ENOENT')console.error('Project bindings could not be restored');}
window=new BrowserWindow({width:1380,height:940,minWidth:760,minHeight:620,title:'yangbin AI Agent',show:false,webPreferences:{preload:path.join(here,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true,webSecurity:true,additionalArguments:['--yangbin-desktop-version='+app.getVersion()]}});
window.webContents.session.setPermissionRequestHandler((_wc,_permission,callback)=>callback(false));
window.webContents.session.setPermissionCheckHandler(()=>false);
window.webContents.on('will-navigate',(event,url)=>{if(new URL(url).origin!==origin)event.preventDefault();});
window.webContents.on('will-attach-webview',event=>event.preventDefault());
window.webContents.setWindowOpenHandler(({url})=>{
  const target=new URL(url);
  const previews=[...spaces.values()].flatMap(s=>[...s.processes.jobs.values()].map(j=>j.url).filter(Boolean));
  if(target.protocol==='https:' || previews.some(p=>new URL(p).origin===target.origin))void shell.openExternal(url);
  return {action:'deny'};
});
Menu.setApplicationMenu(Menu.buildFromTemplate([{label:'应用',submenu:[{label:'重新加载',role:'reload'},{label:'检查更新 / 下载客户端',click:()=>void shell.openExternal(origin+'/download')},{label:'关于 yangbin AI Agent',click:()=>void dialog.showMessageBox(window,{type:'info',title:'关于 yangbin AI Agent',message:'yangbin AI Agent '+app.getVersion(),detail:'本地执行已内置。更新请从应用菜单打开下载页。'})},{type:'separator'},{label:'退出',role:'quit'}]},{label:'编辑',submenu:[{role:'undo'},{role:'redo'},{type:'separator'},{role:'cut'},{role:'copy'},{role:'paste'},{role:'selectAll'}]}]));
window.once('ready-to-show',()=>{if(app.isPackaged || process.env.YANGBIN_DEV_HIDDEN!=='1')window.show();});
window.webContents.on('did-fail-load',(_e,code,_description,_url,isMain)=>{if(isMain&&code!==-3)void dialog.showMessageBox(window,{type:'error',message:'暂时无法连接 yangbin AI Agent',detail:'请检查网络，然后从“应用”菜单重新加载。项目文件不会被删除。'});});
await window.loadURL(origin+'/coding');
app.on('window-all-closed',()=>app.quit());
app.on('before-quit',event=>{
  if(quitting)return;event.preventDefault();quitting=true;
  Promise.allSettled([...spaces.values()].map(s=>s.processes.close())).finally(()=>app.quit());
});
}).catch(error=>{console.error(error);app.quit();});

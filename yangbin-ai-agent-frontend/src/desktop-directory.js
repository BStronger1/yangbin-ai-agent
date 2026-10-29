import {LocalDirectory,localSupported,saveLocalHandle,restoreLocalHandle} from './local-directory'
export const isDesktop = () => globalThis.yangbinDesktop?.version === 1
const native = () => globalThis.yangbinDesktop
function decorate(value) {
  return value ? {...value,queryPermission:async()=> 'granted',requestPermission:async()=> 'granted'} : null
}
export const supportsProjectDirectory = () => isDesktop() || localSupported()
export async function chooseProjectDirectory() {
  if(!isDesktop())return window.showDirectoryPicker({mode:'readwrite',id:'yangbin-coding-project'})
  const value=await native().chooseDirectory()
  if(!value){const error=new Error('已取消选择');error.name='AbortError';throw error}
  return decorate(value)
}
export async function saveProjectHandle(owner,project,handle) {
  return handle?.desktop ? native().bind(owner,project,handle.id) : saveLocalHandle(owner,project,handle)
}
export async function restoreProjectHandle(owner,project) {
  return isDesktop() ? decorate(await native().restore(owner,project)) : restoreLocalHandle(owner,project)
}
class DesktopDirectory {
  constructor(handle){this.handle=handle;this.changes=new Map();this.enabled=true}
  async ready(){if(!this.enabled)throw new Error('本机项目已断开')}
  disconnect(){this.enabled=false;this.changes.clear()}
  async beginTurn(){await this.rpc('begin_turn')}
  async rpc(operation,args={},requestId=crypto.randomUUID(),context){
    await this.ready()
    const result=await native().execute(this.handle.id,operation,args,requestId,context)
    if(result.changes)this.changes=new Map(result.changes.map(c=>[c.path,c]))
    return result
  }
  async files(){return (await this.rpc('files')).files}
  async read(path,{observe=true}={}){return (await this.rpc('read',{path,observe})).output}
  async write(path,content,options={},guard=()=>true){if(!guard())throw new Error('任务已停止');return (await this.rpc(options.edit?'edit':'write',{path,content,...options})).output}
  async execute(operation,args={},guard=()=>true,requestId,context){if(!guard())throw new Error('任务已停止');return (await this.rpc(operation,args,requestId,context)).output}
}
export const projectDirectory = handle => handle?.desktop ? new DesktopDirectory(handle) : new LocalDirectory(handle)
export function desktopProcesses(directory){
  return {desktop:true,workspace:directory.handle.path,platform:directory.handle.platform,
    execute:(operation,args,requestId,context)=>directory.execute(operation,args,()=>true,requestId,context),disconnect(){}}
}

const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('yangbinDesktop',Object.freeze({
  version:2,
  appVersion:process.argv.find(value=>/^--yangbin-desktop-version=\d+\.\d+\.\d+$/.test(value))?.split('=')[1] || null,
  authorizeTask:(id,sessionId,turnId)=>ipcRenderer.invoke('desktop:authorize-task',{id,sessionId,turnId}),
  chooseDirectory:()=>ipcRenderer.invoke('desktop:choose'),
  bind:(owner,project,id)=>ipcRenderer.invoke('desktop:bind',{owner,project,id}),
  restore:(owner,project)=>ipcRenderer.invoke('desktop:restore',{owner,project}),
  execute:(id,operation,args,requestId,context)=>ipcRenderer.invoke('desktop:execute',{id,operation,args,requestId,context}),
  openEditor:id=>ipcRenderer.invoke('desktop:editor',{id})
}));

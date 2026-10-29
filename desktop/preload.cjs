const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('yangbinDesktop',Object.freeze({
  version:1,
  chooseDirectory:()=>ipcRenderer.invoke('desktop:choose'),
  bind:(owner,project,id)=>ipcRenderer.invoke('desktop:bind',{owner,project,id}),
  restore:(owner,project)=>ipcRenderer.invoke('desktop:restore',{owner,project}),
  execute:(id,operation,args,requestId,context)=>ipcRenderer.invoke('desktop:execute',{id,operation,args,requestId,context}),
  openEditor:id=>ipcRenderer.invoke('desktop:editor',{id})
}));

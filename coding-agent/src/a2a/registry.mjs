import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {createRemoteAgent} from './client.mjs';
import {ModelGate} from '../model-gate.mjs';
import {ToolRuntime} from '../tools.mjs';
import {assertIdentity,assertScope} from '../runtime/contracts.mjs';

// Only a server-owned file outside the project can select a remote worker.
// No registry fields or credentials are accepted from prompts or HTTP TurnInput.
export async function loadRemoteRegistry({file,workspace,identity,emit,onGate,onTools,testLoopback=false}) {
  assertIdentity(identity);
  if(!file)return {workerId:'local',resolveRemote:async()=>{throw Error('A2A_REGISTRY_DISABLED');}};
  const root=await fs.realpath(workspace),configPath=await fs.realpath(file);
  if(configPath===root||configPath.startsWith(root+path.sep)||(await fs.stat(configPath)).size>262144)throw Error('A2A_REGISTRY_PATH_DENIED');
  const config=JSON.parse(await fs.readFile(configPath,'utf8'));
  if(!Array.isArray(config.bindings)||config.bindings.length>100)throw Error('A2A_REGISTRY_INVALID');
  const matches=config.bindings.filter(x=>x.accountId===identity.accountId&&x.projectId===identity.projectId);
  if(matches.length>1)throw Error('A2A_REGISTRY_DUPLICATE_SCOPE');
  if(!matches.length)return {workerId:'local',resolveRemote:async()=>{throw Error('A2A_REGISTRY_SCOPE_DENIED');}};
  const binding=matches[0];
  if(!/^[A-Za-z0-9_-]{1,60}$/.test(binding.remoteId)||binding.remoteId==='local'||!Number.isSafeInteger(binding.reservedTokens)||binding.reservedTokens<1||binding.reservedTokens>60000||binding.maxCostMicros!==null&&(!Number.isSafeInteger(binding.maxCostMicros)||binding.maxCostMicros<0)||!Array.isArray(binding.permissions)||binding.permissions.some(p=>!['read','write'].includes(p)))throw Error('A2A_REGISTRY_INVALID');
  return {workerId:binding.remoteId,async resolveRemote(request){
    assertScope(identity,request.identity);
    if(request.identity.sessionId!==identity.sessionId||request.identity.parentTaskId!==identity.taskId||request.executorId!==binding.remoteId)throw Error('A2A_REGISTRY_SCOPE_DENIED');
    const timeoutMs=Math.min(120000,Math.max(1000,binding.timeoutMs||30000));
    const deadline=AbortSignal.any([request.signal,AbortSignal.timeout(timeoutMs)].filter(Boolean));
    const childEmit=event=>emit({...event,childTaskId:request.identity.taskId,agentId:request.identity.agentId});
    const gate=new ModelGate({emit:childEmit,signal:deadline,required:true,budget:request.ledger,identity:request.identity});onGate?.(gate,request.identity);
    const approvals=new ToolRuntime({workspace:request.workspace,allowShell:false,approvalMode:'ask',emit:childEmit,signal:deadline});
    await approvals.init();onTools?.(approvals,request.identity);
    const artifactRoot=path.join(request.sessionDir,'remote-artifacts');await fs.mkdir(artifactRoot,{mode:0o700});
    let remote;
    try{
      remote=createRemoteAgent({identity:request.identity,remoteId:binding.remoteId,url:binding.url,cardUrl:binding.cardUrl,expectedCardHash:binding.expectedCardHash,testLoopback,
        timeoutMs,reservedTokens:binding.reservedTokens,maxCostMicros:binding.maxCostMicros,remotePermissions:binding.permissions,
        credentialProvider:async()=>binding.credential,
        authorize:async value=>{
          value.signal.throwIfAborted();const cancel=()=>approvals.cancel();value.signal.addEventListener('abort',cancel,{once:true});
          try{await approvals.authorize(value.toolName,{remoteId:binding.remoteId,definitionHash:value.definitionHash,argumentsHash:value.argumentsHash,prompt:value.arguments},value.callId,{force:true});value.signal.throwIfAborted();return {approved:true};}
          finally{value.signal.removeEventListener('abort',cancel);}
        },
        budget:{reserve:args=>gate.reserveOperation(args),settle:args=>gate.settleOperation(args)},emit:childEmit,
        storeArtifact:async({identity:child,artifact})=>{
          if(!request.writePaths.includes(artifact.name)||artifact.parts.some(p=>p.content?.$case!=='text'))throw Error('A2A_ARTIFACT_STORAGE_DENIED');
          const bytes=Buffer.from(artifact.parts.map(p=>p.content.value).join('\n'),'utf8');if(bytes.length>65536)throw Error('A2A_ARTIFACT_STORAGE_DENIED');
          const id=randomUUID(),internalPath=path.join(artifactRoot,id+'.txt');await fs.writeFile(internalPath,bytes,{flag:'wx',mode:0o600});
          return {id,taskId:child.taskId,name:artifact.name,size:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex'),internalPath};
        }});
    }catch(error){gate.close();approvals.cancel();throw error;}
    const close=remote.close,execute=remote.execute;return Object.assign(remote,{execute:input=>execute({...input,signal:deadline}),binding:{identity:request.identity,ledger:request.ledger,artifactRoot},close:async()=>{gate.close();approvals.cancel();await close();}});
  }};
}

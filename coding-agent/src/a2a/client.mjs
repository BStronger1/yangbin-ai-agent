import { randomUUID,createHash } from 'node:crypto';
import { AgentCard, Role, TaskState } from '@a2a-js/sdk';
import { ClientFactory,JsonRpcTransportFactory } from '@a2a-js/sdk/client';
import { createMcpTransport,endpoint } from '../mcp/transport.mjs';
import { assertIdentity,assertScope } from '../runtime/contracts.mjs';

const canonical=value=>JSON.stringify(value&&typeof value==='object'?Array.isArray(value)?value.map(v=>JSON.parse(canonical(v))):Object.fromEntries(Object.keys(value).sort().map(k=>[k,JSON.parse(canonical(value[k]))])):value);
export const cardDigest=card=>createHash('sha256').update(canonical(AgentCard.toJSON(AgentCard.fromJSON(AgentCard.toJSON(card))))).digest('hex');
const finalStates=new Set([TaskState.TASK_STATE_COMPLETED,TaskState.TASK_STATE_FAILED,TaskState.TASK_STATE_CANCELED,TaskState.TASK_STATE_REJECTED]);
export function validateCard(card,{url,expectedHash}) {
  if(!card||JSON.stringify(card).length>32768||!Array.isArray(card.supportedInterfaces)||card.supportedInterfaces.length!==1)throw new Error('A2A_CARD_DENIED');
  const face=card.supportedInterfaces[0];
  if(face.url!==url||face.protocolBinding!=='JSONRPC'||face.protocolVersion!=='1.0'||face.tenant||!card.capabilities?.streaming||card.capabilities.extensions?.some(x=>x.required))throw new Error('A2A_CAPABILITY_DENIED');
  if(expectedHash&&cardDigest(card)!==expectedHash)throw new Error('A2A_CARD_CHANGED');
  return card;
}
function validateArtifact(artifact) {
  if(!artifact||typeof artifact.artifactId!=='string'||!artifact.artifactId||artifact.artifactId.length>128||!Array.isArray(artifact.parts)||artifact.parts.length>16||JSON.stringify(artifact).length>65536)throw new Error('A2A_ARTIFACT_DENIED');
  for(const part of artifact.parts){
    if(!['text','data'].includes(part.content?.$case))throw new Error('A2A_ARTIFACT_URL_DENIED');
    if(part.content.$case==='text'&&typeof part.content.value!=='string')throw new Error('A2A_ARTIFACT_DENIED');
    // Data references are inert evidence; arbitrary URL access is never implemented.
  }
  return structuredClone(artifact);
}
/** Trusted fixed remote binding. Remote output is evidence, never a grant of permissions. */
export function createRemoteAgent({identity,remoteId,url,cardUrl,expectedCardHash,credentialProvider,authorize,budget,storeArtifact,emit=()=>{},testLoopback=false,timeoutMs=30000,reservedTokens=4096,maxCostMicros=null,remotePermissions=[]}) {
  assertIdentity(identity);identity=Object.freeze(structuredClone(identity));
  if(!Array.isArray(remotePermissions)||remotePermissions.some(p=>typeof p!=='string'))throw new Error('A2A_PERMISSIONS_REQUIRED');remotePermissions=Object.freeze([...remotePermissions]);
  endpoint(url,{testLoopback});endpoint(cardUrl,{testLoopback});
  if(new URL(cardUrl).origin!==new URL(url).origin||!remoteId||!expectedCardHash||typeof credentialProvider!=='function'||typeof authorize!=='function'||!budget||typeof storeArtifact!=='function')throw new Error('A2A_TRUSTED_BINDING_REQUIRED');
  const known=new Set(),claims=new Set(),secrets=new Set(),controllers=new Set();
  const redact=value=>{let text=JSON.stringify(value);for(const s of secrets)for(const v of [s,encodeURIComponent(s),JSON.stringify(s).slice(1,-1)])text=text.split(v).join('[redacted]');return JSON.parse(text);};
  async function token() {
    const credential=await credentialProvider({identity,remoteId,endpoint:url});
    if(!credential||credential.accountId!==identity.accountId||credential.projectId!==identity.projectId||credential.remoteId!==remoteId||credential.endpoint!==url||!Array.isArray(credential.permissions)||credential.permissions.length!==remotePermissions.length||credential.permissions.some(p=>!remotePermissions.includes(p))||!(credential.expiresAt>Date.now())||typeof credential.accessToken!=='string'||!credential.accessToken||/[\r\n]/.test(credential.accessToken))throw new Error('A2A_AUTH_REQUIRED');
    secrets.add(credential.accessToken);return credential.accessToken;
  }
  const net=createMcpTransport(url,{testLoopback,timeoutMs,maxBytes:524288,token});
  const cards=createMcpTransport(cardUrl,{testLoopback,timeoutMs,maxBytes:32768,token});
  const wireClaims=new Set();
  async function fetchImpl(input,init={}) {
    let body;try{body=JSON.parse(init.body);}catch{}
    if(['SendMessage','SendStreamingMessage'].includes(body?.method)){
      const id=body.params?.message?.messageId;if(!id||wireClaims.has(id))throw new Error('A2A_REPLAY_DENIED');wireClaims.add(id);
    }
    return net.fetch(input,init);
  }
  let client;
  async function discover() {
    const response=await cards.fetch(cardUrl,{headers:{'A2A-Version':'1.0'}});
    if(!response.ok)throw new Error('A2A_DISCOVERY_FAILED');
    const card=AgentCard.fromJSON(await response.json());validateCard(card,{url,expectedHash:expectedCardHash});
    client=await new ClientFactory({transports:[new JsonRpcTransportFactory({fetchImpl})],preferredTransports:['JSONRPC']}).createFromAgentCard(card);
    return {remoteId,protocolVersion:'1.0',cardHash:cardDigest(card),streaming:true};
  }
  const check=id=>{if(!known.has(id))throw new Error('A2A_TASK_BINDING_DENIED');};
  async function get(id){check(id);return redact(await client.getTask({id}));}
  async function cancel(id){check(id);return redact(await client.cancelTask({id},{signal:AbortSignal.timeout(2000)}));}
  async function execute(request) {
    assertScope(identity,request.identity);assertIdentity(request.identity);
    if(!Array.isArray(request.permissions)||remotePermissions.some(p=>!request.permissions.includes(p))||controllers.size)throw new Error('A2A_PERMISSION_OR_CONCURRENCY_DENIED');
    if(typeof request.prompt!=='string'||request.prompt.length>32000||!request.prompt.trim()||claims.has(request.identity.taskId))throw new Error('A2A_REQUEST_DENIED');
    claims.add(request.identity.taskId);
    const abort=new AbortController();controllers.add(abort);
    const signal=AbortSignal.any([request.signal,abort.signal,AbortSignal.timeout(timeoutMs)].filter(Boolean));
    let lease,remoteTaskId,terminal,sent=false,settled=false;const outputArtifacts=new Map();
    try{
      signal.throwIfAborted();await discover();signal.throwIfAborted();
      const grant=await authorize({identity:request.identity,toolName:'a2a__'+remoteId,definitionHash:expectedCardHash,argumentsHash:createHash('sha256').update(request.prompt).digest('hex'),arguments:request.prompt,callId:request.identity.taskId,signal});
      if(grant?.approved!==true)throw new Error('A2A_APPROVAL_DENIED');
      signal.throwIfAborted();await discover();signal.throwIfAborted(); // invalidate card drift during approval
      lease=await budget.reserve({identity:request.identity,kind:'a2a',requestId:'remote:'+request.identity.taskId,reservedTokens,maxCostMicros});signal.throwIfAborted();sent=true;
      const message={messageId:randomUUID(),role:Role.ROLE_USER,parts:[{content:{$case:'text',value:request.prompt},mediaType:'text/plain',filename:''}],contextId:'',taskId:'',extensions:[],referenceTaskIds:[]};
      let events=0;
      for await(const event of client.sendMessageStream({message,configuration:{acceptedOutputModes:['text/plain','application/json'],returnImmediately:false}},{signal})){
        if(++events>256)throw new Error('A2A_EVENT_LIMIT');
        const { $case:kind,value }=event.payload||{};
        const id=kind==='task'?value.id:value?.taskId;
        if(typeof id!=='string'||!id||id.length>160||remoteTaskId&&id!==remoteTaskId)throw new Error('A2A_TASK_BINDING_DENIED');
        if(!remoteTaskId){remoteTaskId=id;known.add(id);}
        const incoming=kind==='task'?value.artifacts||[]:kind==='artifactUpdate'?[value.artifact]:[];
        for(const a of incoming){validateArtifact(a);if(outputArtifacts.size>=32&&!outputArtifacts.has(a.artifactId))throw new Error('A2A_ARTIFACT_LIMIT');if(kind==='artifactUpdate'&&value.append)throw new Error('A2A_ARTIFACT_APPEND_UNSUPPORTED');outputArtifacts.set(a.artifactId,redact(a));}
        const state=kind==='task'?value.status?.state:kind==='statusUpdate'?value.status?.state:undefined;
        if(state!==undefined){if(terminal)throw new Error('A2A_POST_TERMINAL_EVENT');if(finalStates.has(state))terminal=state;if([TaskState.TASK_STATE_INPUT_REQUIRED,TaskState.TASK_STATE_AUTH_REQUIRED].includes(state))throw new Error('A2A_REMOTE_INPUT_REQUIRED');}
        if(!['task','statusUpdate','artifactUpdate'].includes(kind))throw new Error('A2A_EVENT_UNSUPPORTED');
        emit({type:'a2a',remoteId,taskId:request.identity.taskId,remoteTaskId,status:state??null});
      }
      if(!terminal)throw new Error('A2A_MISSING_TERMINAL');
      const artifacts=[];
      for(const artifact of outputArtifacts.values()){
        const stored=await storeArtifact({identity:request.identity,remoteId,remoteTaskId,artifact,trust:'untrusted-external'});
        if(!stored||stored.taskId!==request.identity.taskId||stored.url||!/^[a-f0-9]{64}$/.test(stored.sha256)||!Number.isSafeInteger(stored.size))throw new Error('A2A_ARTIFACT_STORAGE_DENIED');
        artifacts.push(stored);
      }
      const status=terminal===TaskState.TASK_STATE_COMPLETED?'SUCCEEDED':terminal===TaskState.TASK_STATE_CANCELED?'CANCELLED':'FAILED';
      settled=true;await budget.settle({reservationId:lease,usage:null,status});
      return {status,artifacts,error:status==='FAILED'?'Remote task failed':null,evidence:{remoteId,remoteTaskId,protocolVersion:'1.0',trust:'untrusted-external',events}};
    }catch(error){
      if(sent&&remoteTaskId&&!terminal)await cancel(remoteTaskId).catch(()=>{});
      if(lease&&!settled)await budget.settle({reservationId:lease,usage:sent?null:{inputTokens:0,outputTokens:0,costMicros:0},status:'UNKNOWN'});
      const code=/^A2A_[A-Z_]+$/.test(error.message)?error.message:signal.aborted?'A2A_CANCELLED':'A2A_REMOTE_FAILED';
      return {status:signal.aborted?'CANCELLED':'FAILED',artifacts:[],error:code,evidence:{remoteId,remoteTaskId:remoteTaskId||null,outcome:sent?'UNKNOWN':'NOT_SENT'}};
    }finally{controllers.delete(abort);}
  }
  async function close(){for(const c of controllers)c.abort();await Promise.allSettled([net.close(),cards.close()]);secrets.clear();}
  return{discover,get,cancel,execute,close};
}




import { randomUUID, createHash } from 'node:crypto';
import express from 'express';
import { TaskState, Role } from '@a2a-js/sdk';
import { jsonRpcHandler, agentCardHandler } from '@a2a-js/sdk/server/express';
import { TaskNotFoundError, TaskNotCancelableError, RequestMalformedError, UnsupportedOperationError, VersionNotSupportedError } from '@a2a-js/sdk/errors';
import { assertIdentity, childIdentity, TERMINAL } from '../runtime/contracts.mjs';
import { endpoint } from '../mcp/transport.mjs';

export const A2A_VERSION='1.0';
const states={QUEUED:TaskState.TASK_STATE_SUBMITTED,RUNNING:TaskState.TASK_STATE_WORKING,WAITING_APPROVAL:TaskState.TASK_STATE_AUTH_REQUIRED,BLOCKED:TaskState.TASK_STATE_INPUT_REQUIRED,SUCCEEDED:TaskState.TASK_STATE_COMPLETED,FAILED:TaskState.TASK_STATE_FAILED,CANCELLED:TaskState.TASK_STATE_CANCELED,PARTIAL:TaskState.TASK_STATE_FAILED,INTERRUPTED:TaskState.TASK_STATE_FAILED};
const part=value=>({content:{$case:'data',value},mediaType:'application/json',filename:'',metadata:undefined});
export function createA2aServer({url,authenticate,taskStore,executor,budget,permissions=[],testLoopback=false,maxDurationMs=60000}) {
  endpoint(url,{testLoopback});
  if(typeof authenticate!=='function'||typeof executor?.execute!=='function'||!taskStore||!budget)throw new Error('A2A_TRUSTED_ADAPTERS_REQUIRED');
  const owners=new Map(),contexts=new Map(),replay=new Map(),listeners=new Map(),runs=new Set();
  const card={name:'yangbin-controlled-agent',description:'Controlled task execution. Authorization is determined by the server.',version:'1.0.0',
    supportedInterfaces:[{url,protocolBinding:'JSONRPC',protocolVersion:A2A_VERSION,tenant:''}],
    capabilities:{streaming:true,pushNotifications:false,extendedAgentCard:false,extensions:[]},
    defaultInputModes:['text/plain'],defaultOutputModes:['application/json'],
    skills:[{id:'execute',name:'Execute authorized task',description:'Uses the existing controlled task executor.',tags:['task'],examples:[],inputModes:['text/plain'],outputModes:['application/json'],securityRequirements:[]}],
    securitySchemes:{bearer:{httpAuthSecurityScheme:{scheme:'bearer',bearerFormat:''}}},securityRequirements:[{schemes:{bearer:{list:[]}}}],signatures:[]};
  const actor=ctx=>{if(ctx?.requestedVersion!=='1.0')throw new VersionNotSupportedError('Only protocol 1.0 is supported');const user=ctx?.user;if(!user?.isAuthenticated||!user.identity||!user.principalId)throw new TaskNotFoundError('Task not found');return user;};
  function get(ctx,id){const user=actor(ctx);if(owners.get(id)!==user.principalId)throw new TaskNotFoundError('Task not found');try{return taskStore.get(user.identity,id);}catch{throw new TaskNotFoundError('Task not found');}}
  function project(task){return{id:task.identity.taskId,contextId:contexts.get(task.identity.taskId),status:{state:states[task.status],timestamp:undefined,message:undefined},artifacts:task.artifacts.map(a=>({artifactId:a.id,name:a.name,description:'Controlled artifact reference',parts:[part(a)],metadata:undefined,extensions:[]})),history:[],metadata:{runtimeStatus:task.status}};}
  function notify(id){for(const wake of listeners.get(id)||[])wake();}
  async function* stream(ctx,id) {
    let version=-1;const seenArtifacts=new Set();
    while(true){
      const task=get(ctx,id);
            if(task.revision!==version){
        const first=version===-1;version=task.revision;
        if(first)yield{payload:{$case:'task',value:project(task)}};
        else {
          for(const artifact of project(task).artifacts)if(!seenArtifacts.has(artifact.artifactId)){seenArtifacts.add(artifact.artifactId);yield{payload:{$case:'artifactUpdate',value:{taskId:id,contextId:contexts.get(id),artifact,append:false,lastChunk:true}}};}
          yield{payload:{$case:'statusUpdate',value:{taskId:id,contextId:contexts.get(id),status:project(task).status}}};
        }
      }
      if(TERMINAL.has(task.status)||['BLOCKED','WAITING_APPROVAL'].includes(task.status))return;
      let wake;const ready=new Promise(resolve=>{wake=resolve;});
      if(!listeners.has(id))listeners.set(id,new Set());listeners.get(id).add(wake);
      try{if(get(ctx,id).revision===version)await ready;}finally{listeners.get(id)?.delete(wake);if(!listeners.get(id)?.size)listeners.delete(id);}
    }
  }
  async function start(params,ctx) {
    const user=actor(ctx),message=params.message;
    if(params.tenant || params.configuration?.taskPushNotificationConfig)throw new UnsupportedOperationError('Tenant selection and push notifications are unsupported');
    if(!message||message.role!==Role.ROLE_USER||!message.messageId||message.messageId.length>128||!Array.isArray(message.parts)||message.parts.length<1||message.parts.length>16)throw new RequestMalformedError('Text message required');
    if(message.taskId){get(ctx,message.taskId);throw new UnsupportedOperationError('Continuing an existing task is unsupported');}
    if(message.contextId||message.referenceTaskIds?.length||message.extensions?.length)throw new UnsupportedOperationError('Caller-supplied contexts and references are unsupported');
    if(message.parts.some(p=>p.content?.$case!=='text'||typeof p.content.value!=='string'))throw new RequestMalformedError('Only text parts are supported');
    const prompt=message.parts.map(p=>p.content.value).join('\n');if(!prompt.trim()||prompt.length>32000)throw new RequestMalformedError('Invalid text size');
    const fingerprint=createHash('sha256').update(prompt).digest('hex'),replayKey=JSON.stringify([user.principalId,user.identity.accountId,user.identity.projectId,message.messageId]);
    if(replay.has(replayKey)){const old=replay.get(replayKey);if(old.fingerprint!==fingerprint)throw new RequestMalformedError('Message ID reused');return old.id;}
    if(owners.size>=1000)throw new UnsupportedOperationError('Task capacity reached');
    const identity=childIdentity(user.identity);
    const granted=permissions.filter(p=>user.permissions?.includes(p));
    taskStore.create(identity,{permissions:granted});owners.set(identity.taskId,user.principalId);contexts.set(identity.taskId,randomUUID());replay.set(replayKey,{id:identity.taskId,fingerprint});
    const work=(async()=>{
      let lease;
      try{
        // The executor shares the same budget; this lease reserves delegation overhead only.
        lease=budget.reserve({identity,kind:'a2a-inbound',requestId:'inbound:'+identity.taskId,reservedTokens:1,maxCostMicros:0});
        taskStore.transition(identity,identity.taskId,'RUNNING');notify(identity.taskId);
        const timeout=AbortSignal.timeout(Math.max(50,Math.min(maxDurationMs,3600000)));
        const signal=AbortSignal.any([taskStore.signal(identity,identity.taskId),timeout]);
        const result=await Promise.race([executor.execute({identity,prompt,permissions:granted,signal,budget,emit:event=>{const task=taskStore.get(identity,identity.taskId);if(!TERMINAL.has(task.status)&&event?.type==='progress'&&typeof event.phase==='string'){taskStore.transition(identity,identity.taskId,'RUNNING',{progress:{phase:event.phase.slice(0,128)}});notify(identity.taskId);}},context:[]}),new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(new Error('Execution stopped')),{once:true}))]);
        const active=taskStore.get(identity,identity.taskId);
        if(!TERMINAL.has(active.status)){
          for(const artifact of result.artifacts||[])taskStore.addArtifact(identity,identity.taskId,artifact);
          const status=TERMINAL.has(result.status)?result.status:'FAILED';taskStore.transition(identity,identity.taskId,status,{error:status==='FAILED'?'Execution failed':null});
        }
        budget.settle({reservationId:lease,usage:{inputTokens:0,outputTokens:0,costMicros:0},status:taskStore.get(identity,identity.taskId).status==='SUCCEEDED'?'SUCCEEDED':'FAILED'});lease=null;
      }catch{
        const active=taskStore.get(identity,identity.taskId);if(!TERMINAL.has(active.status))taskStore.transition(identity,identity.taskId,'FAILED',{error:'Execution failed or interrupted'});
        if(lease)budget.settle({reservationId:lease,usage:null,status:'UNKNOWN'});
      }finally{notify(identity.taskId);}
    })();runs.add(work);void work.finally(()=>runs.delete(work));return identity.taskId;
  }
  const unsupported=async()=>{throw new UnsupportedOperationError('Optional capability unsupported');};
  const handler={
    getAgentCard:async()=>structuredClone(card),getAuthenticatedExtendedAgentCard:unsupported,
    async sendMessage(params,ctx){const id=await start(params,ctx);if(!params.configuration?.returnImmediately)for await(const _ of stream(ctx,id)){}return project(get(ctx,id));},
    async *sendMessageStream(params,ctx){const id=await start(params,ctx);yield* stream(ctx,id);},
    getTask:async(params,ctx)=>project(get(ctx,params.id)),
    async cancelTask(params,ctx){const task=get(ctx,params.id);if(TERMINAL.has(task.status))throw new TaskNotCancelableError('Task is terminal');taskStore.cancel(actor(ctx).identity,params.id);notify(params.id);return project(get(ctx,params.id));},
    async *resubscribe(params,ctx){yield*stream(ctx,params.id);},
    async listTasks(params,ctx){if(params.pageToken||params.statusTimestampAfter)throw new UnsupportedOperationError('Paging tokens and time filters unsupported');const user=actor(ctx);let tasks=taskStore.list(user.identity).filter(t=>owners.get(t.identity.taskId)===user.principalId).map(project);if(params.contextId)tasks=tasks.filter(t=>t.contextId===params.contextId);if(params.status)tasks=tasks.filter(t=>t.status.state===params.status);const pageSize=Math.max(1,Math.min(params.pageSize||50,100));if(tasks.length>pageSize)throw new UnsupportedOperationError('Narrow query; paginated listing unsupported');if(!params.includeArtifacts)tasks=tasks.map(t=>({...t,artifacts:[]}));return{tasks,nextPageToken:'',pageSize,totalSize:tasks.length};},
    createTaskPushNotificationConfig:unsupported,getTaskPushNotificationConfig:unsupported,listTaskPushNotificationConfigs:unsupported,deleteTaskPushNotificationConfig:unsupported,
  };
  const app=express();app.disable('x-powered-by');
  app.use(async(req,res,next)=>{try{const bound=await authenticate(req);assertIdentity(bound?.identity);if(!bound.principalId||!Array.isArray(bound.permissions))throw new Error();req.a2aBound={...bound,identity:Object.freeze(structuredClone(bound.identity))};next();}catch{res.status(401).json({error:'Authentication required'});}});
  app.use((req,res,next)=>{if(req.method==='GET'&&req.headers['a2a-version']!=='1.0'){res.status(400).json({error:'A2A-Version 1.0 required'});return;}next();});
  app.use('/.well-known/agent-card.json',agentCardHandler({agentCardProvider:handler}));
  app.use(express.json({limit:'64kb'}));
  app.use(new URL(url).pathname,jsonRpcHandler({requestHandler:handler,userBuilder:async req=>({isAuthenticated:true,userName:req.a2aBound.principalId,...req.a2aBound})}));
  app.use((err,req,res,next)=>{if(!res.headersSent)res.status(400).json({error:'Invalid request'});});
  async function close(){for(const [id]of owners){const t=taskStore.tasks.get(id);if(t&&!TERMINAL.has(t.status)){taskStore.cancel(t.identity,id);notify(id);}}await Promise.allSettled([...runs]);}
  return{app,handler,card,close};
}





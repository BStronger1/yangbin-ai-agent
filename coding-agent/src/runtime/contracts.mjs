import { randomUUID } from 'node:crypto';
import { appendFileSync, openSync, closeSync, fsyncSync, readFileSync, existsSync } from 'node:fs';

export const TERMINAL = new Set(['SUCCEEDED','FAILED','CANCELLED','PARTIAL','INTERRUPTED']);
const ACTIVE = new Set(['QUEUED','RUNNING','WAITING_APPROVAL','BLOCKED']);
const safeId = value => typeof value === 'string' && /^[a-zA-Z0-9._:-]{1,160}$/.test(value);
const natural = value => Number.isSafeInteger(value) && value >= 0;
export function assertIdentity(identity) {
  for (const key of ['accountId','projectId','sessionId','taskId','agentId']) if (!safeId(identity?.[key])) throw new Error(`Invalid trusted identity: ${key}`);
  for (const key of ['parentTaskId','parentAgentId']) if (identity[key] != null && !safeId(identity[key])) throw new Error(`Invalid parent identity: ${key}`);
  return identity;
}
export function sameScope(a,b) { return a.accountId === b.accountId && a.projectId === b.projectId; }
export function assertScope(identity,target) { assertIdentity(identity); if (!sameScope(identity,target)) throw new Error('Task scope denied'); }
export function childIdentity(parent) {
  assertIdentity(parent);
  return Object.freeze({ accountId:parent.accountId, projectId:parent.projectId, sessionId:parent.sessionId, taskId:randomUUID(), agentId:randomUUID(), parentTaskId:parent.taskId, parentAgentId:parent.agentId });
}
export function intersectPermissions(parent,requested) {
  if (!Array.isArray(parent) || !Array.isArray(requested) || parent.some(x=>typeof x!=='string') || requested.some(x=>typeof x!=='string')) throw new Error('Invalid permissions');
  if (requested.some(x=>!parent.includes(x))) throw new Error('Delegation cannot increase permissions');
  return Object.freeze([...new Set(requested)]);
}

// One instance is shared by all local executors. Mutations never await: concurrent
// promises cannot race a reservation. The parent Java governor remains the
// account-wide authority across processes. Journal is trusted, outside workspaces.
export class BudgetLedger {
  constructor({maxRequests,maxTokens,maxCostMicros=null,journal=null}={}) {
    if (!natural(maxRequests)||!maxRequests||!natural(maxTokens)||!maxTokens||maxCostMicros!==null&&!natural(maxCostMicros)) throw new Error('Invalid budget');
    this.limits={maxRequests,maxTokens,maxCostMicros}; this.requests=0; this.tokens=0; this.cost=0; this.unknownCosts=0; this.entries=new Map(); this.ids=new Set(); this.journal=journal;
    // A previous STARTED ledger must be reviewed, never silently resumed/replayed.
    if (journal) { if(existsSync(journal)&&readFileSync(journal,'utf8').trim()) throw new Error('Existing budget ledger requires reconciliation'); const fd=openSync(journal,'wx',0o600); closeSync(fd); }
  }
  record(event) { if(this.journal) { const fd=openSync(this.journal,'a',0o600); try { appendFileSync(fd,JSON.stringify(event)+'\n'); fsyncSync(fd); } finally {closeSync(fd);} } }
  reserve({identity,kind='model',requestId,reservedTokens,maxCostMicros=null}) {
    assertIdentity(identity);
    if (!safeId(requestId)||this.ids.has(requestId)||!natural(reservedTokens)||!reservedTokens) throw new Error('Invalid or duplicate reservation');
    if (maxCostMicros!==null&&!natural(maxCostMicros)) throw new Error('Invalid cost reservation');
    if(this.limits.maxCostMicros!==null && maxCostMicros===null) throw new Error('Cost estimate required for monetary budget');
    if(this.requests+1>this.limits.maxRequests||this.tokens+reservedTokens>this.limits.maxTokens||this.limits.maxCostMicros!==null&&this.cost+maxCostMicros>this.limits.maxCostMicros) throw new Error('Global budget exhausted');
    const id=randomUUID(),entry={id,identity:{...identity},kind,requestId,reservedTokens,maxCostMicros,status:'STARTED',usage:null};
    this.record(entry); this.entries.set(id,entry); this.ids.add(requestId); this.requests++; this.tokens+=reservedTokens; this.cost+=maxCostMicros??0; if(maxCostMicros===null)this.unknownCosts++;
    return id;
  }
  settle({reservationId,usage=null,status='UNKNOWN'}) {
    const entry=this.entries.get(reservationId);
    if(!entry||entry.status!=='STARTED'||!['SUCCEEDED','FAILED','CANCELLED','UNKNOWN'].includes(status)) throw new Error('Invalid settlement');
    const known=usage&&natural(usage.inputTokens)&&natural(usage.outputTokens);
    if(usage!==null&&!known) throw new Error('Invalid usage; use null for unknown');
    const actual=known?usage.inputTokens+usage.outputTokens:entry.reservedTokens;
    if(!natural(actual))throw new Error('Invalid usage total');
    if(usage?.costMicros!=null&&!natural(usage.costMicros))throw new Error('Invalid actual cost');
    const settled={...entry,status,usage:known?{...usage}:null}; this.record(settled); this.entries.set(reservationId,settled);
    // Actual usage can exceed the estimate: record it and prevent further calls.
    this.tokens+=actual-entry.reservedTokens;
    if(usage?.costMicros!=null){this.cost+=usage.costMicros-(entry.maxCostMicros??0);if(entry.maxCostMicros===null)this.unknownCosts--;}
    return this.snapshot();
  }
  snapshot(){return Object.freeze({requests:this.requests,tokens:this.tokens,costMicros:this.unknownCosts?'UNKNOWN':this.cost,reservedCostMicros:this.cost,unknownCosts:this.unknownCosts,...this.limits});}
}

export class TaskStore {
  constructor({emit=()=>{},clock=()=>new Date().toISOString()}={}){this.tasks=new Map();this.emit=emit;this.clock=clock;this.sequence=0;}
  create(identity,{permissions=[],parent=null}={}){
    assertIdentity(identity);if(this.tasks.has(identity.taskId))throw new Error('Task already exists');
    if(parent){assertScope(parent,identity);if(identity.parentTaskId!==parent.taskId||identity.parentAgentId!==parent.agentId)throw new Error('Parent binding mismatch');const p=this.get(parent,parent.taskId);if(TERMINAL.has(p.status))throw new Error('Parent is terminal');permissions=intersectPermissions(p.permissions,permissions);}
    const task={identity:Object.freeze({...identity}),status:'QUEUED',revision:1,permissions:[...permissions],artifacts:[],progress:null,error:null,controller:new AbortController()};this.tasks.set(identity.taskId,task);this.event(task,'created');return this.view(task);
  }
  get(actor,id){const task=this.tasks.get(id);if(!task)throw new Error('Task not found');assertScope(actor,task.identity);return task;}
  view(task){const {controller,...value}=task;return structuredClone(value);}
  event(task,type){this.emit({type:'task',event:type,seq:++this.sequence,createdAt:this.clock(),...this.view(task)});}
  transition(actor,id,status,{progress=null,error=null}={}){const task=this.get(actor,id);if(TERMINAL.has(task.status))throw new Error('Terminal task cannot transition');if(!ACTIVE.has(status)&&!TERMINAL.has(status))throw new Error('Unknown status');if(task.status==='QUEUED'&&status==='SUCCEEDED')throw new Error('Task must execute before success');task.status=status;task.revision++;task.progress=progress;task.error=error;this.event(task,'status');return this.view(task);}
  cancel(actor,id){const task=this.get(actor,id);if(TERMINAL.has(task.status))return this.view(task);for(const child of this.tasks.values())if(child.identity.parentTaskId===id)this.cancel(actor,child.identity.taskId);task.controller.abort(new Error('Task cancelled'));return this.transition(actor,id,'CANCELLED');}
  addArtifact(actor,id,artifact){const task=this.get(actor,id);if(TERMINAL.has(task.status))throw new Error('Task is terminal');if(!safeId(artifact?.id)||artifact.taskId!==id||!natural(artifact.size)||!/^[a-f0-9]{64}$/.test(artifact.sha256)||typeof artifact.name!=='string'||artifact.name.length>256||artifact.url)throw new Error('Invalid controlled artifact');if(task.artifacts.some(a=>a.id===artifact.id))throw new Error('Duplicate artifact');task.artifacts.push(structuredClone(artifact));task.revision++;this.event(task,'artifact');}
  list(actor){assertIdentity(actor);return [...this.tasks.values()].filter(t=>sameScope(actor,t.identity)).map(t=>this.view(t));}
  signal(actor,id){return this.get(actor,id).controller.signal;}
}

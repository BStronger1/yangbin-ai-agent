import fs from 'node:fs/promises';
import path from 'node:path';
import {isDeepStrictEqual} from 'node:util';
import {sha256} from './guards.mjs';

const taskIds=['N02','N03','N04'];
const digest=value=>typeof value==='string'&&/^[a-f0-9]{64}$/i.test(value);
const integer=value=>Number.isSafeInteger(value)&&value>=0;
const equal=(a,b,label)=>{if(!isDeepStrictEqual(a,b))throw Error('Parent verification: '+label);};
async function regular(file) {
 const stat=await fs.lstat(file);
 if(stat.isSymbolicLink()||!stat.isFile()||stat.size>8*1024*1024)throw Error('Parent verification: invalid evidence file');
 return fs.readFile(file);
}
async function directory(folder) {
 const stat=await fs.lstat(folder);
 if(stat.isSymbolicLink()||!stat.isDirectory())throw Error('Parent verification: invalid evidence directory');
}
async function pinned(file,expected,label) {
 if(!digest(expected))throw Error('Parent verification: missing '+label+' hash');
 const bytes=await regular(file);
 if(sha256(bytes)!==expected.toLowerCase())throw Error('Parent verification: '+label+' hash mismatch');
 return bytes;
}
function json(bytes,label) {try{return JSON.parse(bytes.toString('utf8'));}catch{throw Error('Parent verification: invalid '+label+' JSON');}}
function journalRows(bytes,parentProtocol) {
 const text=bytes.toString('utf8');
 if(!text.endsWith('\n'))throw Error('Parent verification: partial journal');
 const starts=new Map(),finishes=new Map();
 for(const line of text.slice(0,-1).split('\n')) {
  if(!line.trim())throw Error('Parent verification: empty journal row');
  const row=json(Buffer.from(line),'journal');
  if(!row||typeof row!=='object'||Array.isArray(row)||typeof row.requestId!=='string'||!/^[a-f0-9-]{36}$/.test(row.requestId))throw Error('Parent verification: invalid request identifier');
  if(!parentProtocol.taskOrder.includes(row.taskId)||!Object.hasOwn(parentProtocol.groups,row.group)||![1,2].includes(row.stage))throw Error('Parent verification: invalid request scope');
  if(row.type==='REQUEST_STARTED') {
   if(starts.has(row.requestId))throw Error('Parent verification: duplicate started request');
   if(taskIds.includes(row.taskId))throw Error('Parent verification: child task already started');
   if(row.taskId!=='N01'||row.group!=='A')throw Error('Parent verification: unexpected parent cell');
   if(!Number.isSafeInteger(row.requestBytes)||row.requestBytes<=0||!Number.isSafeInteger(row.reservedTokens)||row.reservedTokens<=0||row.actualMaxOutputTokens!==2048||row.reservedTokens!==row.requestBytes+2048||typeof row.summary!=='boolean')throw Error('Parent verification: invalid reservation');
   starts.set(row.requestId,row);
  } else if(row.type==='REQUEST_FINISHED') {
   const started=starts.get(row.requestId);
   if(!started||finishes.has(row.requestId)||row.taskId!==started.taskId||row.group!==started.group||row.stage!==started.stage)throw Error('Parent verification: unmatched or duplicate finish');
   if(!['SUCCEEDED','FAILED','CANCELLED','UNKNOWN'].includes(row.status)||!Object.hasOwn(row,'inputTokens')||!Object.hasOwn(row,'outputTokens')||![row.inputTokens,row.outputTokens].every(value=>value===null||integer(value)))throw Error('Parent verification: invalid finished usage');
   finishes.set(row.requestId,row);
  } else throw Error('Parent verification: invalid journal event');
 }
 return {starts,finishes,requests:starts.size,reservedTokens:[...starts.values()].reduce((sum,row)=>sum+row.reservedTokens,0),
  unknownUsageRequests:[...starts.keys()].filter(id=>!finishes.has(id)||finishes.get(id).inputTokens===null||finishes.get(id).outputTokens===null).length};
}

/** Runs before either child ledger claim or any provider request. Facts/limits are inherited, never expanded. */
export async function verifyParent({authorizationPath,runDirectory,protocol,frozenSha256,endpointSha256,here}) {
 if(!path.isAbsolute(authorizationPath??'')||!path.isAbsolute(runDirectory??'')||!path.isAbsolute(here??''))throw Error('Parent verification: absolute paths required');
 const child=json(await regular(authorizationPath),'child authorization');
 if(child.status!=='APPROVED'||child.batchId!==protocol.id||child.frozenSha256!==frozenSha256||!digest(frozenSha256)||child.endpointSha256!==endpointSha256||!digest(endpointSha256))throw Error('Parent verification: child authorization mismatch');
 if(!path.isAbsolute(child.runDirectory??'')||path.resolve(child.runDirectory)!==path.resolve(runDirectory)||child.maxRequests!==96||child.maxReservedTokens!==2304000||!path.isAbsolute(child.ledgerDirectory??''))throw Error('Parent verification: child scope or budget mismatch');
 if(!path.isAbsolute(child.parentDirectory??''))throw Error('Parent verification: parent directory required');
 const parentDir=path.resolve(child.parentDirectory);await directory(parentDir);await directory(path.join(parentDir,'real-1'));
 const source=path.join(parentDir,'evaluation','memory-m030');await directory(path.join(parentDir,'evaluation'));await directory(source);
 const parent=protocol.parent;
 if(!parent||parent.id!=='memory-m030-v1'||protocol.id!=='memory-m030-unrun-v1'||parent.requireCompletedMarker!==true)throw Error('Parent verification: inherited protocol mismatch');
 equal(parent.requireNoStartedRequestsFor,taskIds,'unstarted scope');equal(parent.excludedTaskIds,['N01'],'excluded scope');
 const claimFile=path.join(parentDir,'UNRUN_SUBSET_CLAIMED');
 try{await fs.lstat(claimFile);throw Error('Parent verification: prior child claim');}catch(error){if(error.code!=='ENOENT')throw error;}
 const completed=await pinned(path.join(parentDir,'real-1','COMPLETED'),child.parentCompletedSha256,'completed');
 if(!completed.toString('utf8').trim())throw Error('Parent verification: empty completion marker');
 const parentAuthorizationBytes=await pinned(path.join(parentDir,'authorization.json'),child.parentAuthorizationSha256,'authorization');
 const approval=json(parentAuthorizationBytes,'parent authorization');
 if(approval.status!=='APPROVED'||approval.batchId!==parent.id||approval.endpointSha256!==endpointSha256||approval.maxRequests!==128||approval.maxReservedTokens!==3072000||!path.isAbsolute(approval.runDirectory??'')||path.resolve(approval.runDirectory)!==path.join(parentDir,'real-1'))throw Error('Parent verification: parent approval mismatch');
 const parentFrozen=await pinned(path.join(source,'frozen.json'),parent.frozenSha256,'source frozen');
 if(approval.frozenSha256!==sha256(parentFrozen))throw Error('Parent verification: parent authorization freeze mismatch');
 const frozen=json(parentFrozen,'parent frozen');
 const requiredSources=['protocol.json','tasks.json','run.mjs','guards.mjs','scoring.mjs','fixtures.mjs','offline.mjs'];
 equal(Object.keys(parent.sourceHashes??{}).sort(),[...requiredSources].sort(),'source hash coverage');
 for(const name of requiredSources){const bytes=await pinned(path.join(source,name),parent.sourceHashes[name],'source '+name);if(frozen.files?.[name]!==sha256(bytes))throw Error('Parent verification: source absent from original freeze');}
 const original=json(await regular(path.join(source,'protocol.json')),'source protocol');
 const originalTasks=json(await regular(path.join(source,'tasks.json')),'source tasks');
 const childTasks=json(await regular(path.join(here,'tasks.json')),'child tasks');
 equal(protocol.taskOrder,taskIds,'child task order');equal(Object.keys(protocol.executionOrders),taskIds,'child execution coverage');equal(protocol.artifactTaskIds,[],'child artifacts');
 equal(childTasks,originalTasks.filter(task=>taskIds.includes(task.id)),'task objects changed');
 equal(original.id,parent.id,'parent identifier');equal(original.status,'frozen','parent not frozen');
 for(const [name,value] of Object.entries(original)){
  if(!['id','status','taskOrder','executionOrders','artifactTaskIds','budget','claims','parent'].includes(name))equal(protocol[name],value,'inherited field '+name);
 }
 for(const id of taskIds)equal(protocol.executionOrders[id],original.executionOrders[id],'rotation changed');
 equal(original.budget.maxRequests,128,'original request authorization');equal(original.budget.maxReservedTokens,3072000,'original token authorization');
 for(const [name,value] of Object.entries(original.budget))if(!['maxRequests','maxReservedTokens'].includes(name))equal(protocol.budget[name],value,'per-instance limit changed');
 equal(protocol.budget.maxRequests,96,'child request limit');equal(protocol.budget.maxReservedTokens,2304000,'child token limit');
 const journal=await pinned(path.join(parentDir,'real-1','requests.jsonl'),child.parentRequestsSha256,'journal');
 const usage=journalRows(journal,original);
 if(usage.requests!==7||usage.reservedTokens!==176892||!Number.isSafeInteger(usage.reservedTokens))throw Error('Parent verification: consumed totals mismatch');
 equal(parent.consumedRequests,usage.requests,'declared consumed requests');equal(parent.consumedReservedTokens,usage.reservedTokens,'declared consumed tokens');
 equal(parent.authorizedMaxRequests,128,'declared parent requests');equal(parent.authorizedMaxReservedTokens,3072000,'declared parent tokens');
 equal(parent.combinedMaxRequests,usage.requests+protocol.budget.maxRequests,'declared combined requests');equal(parent.combinedMaxReservedTokens,usage.reservedTokens+protocol.budget.maxReservedTokens,'declared combined tokens');
 if(usage.requests+protocol.budget.maxRequests>approval.maxRequests||usage.reservedTokens+protocol.budget.maxReservedTokens>approval.maxReservedTokens)throw Error('Parent verification: inherited budget exceeded');
 return {claimFile,safe:{parentId:parent.id,parentFrozenSha256:sha256(parentFrozen),parentRequestsSha256:sha256(journal),parentAuthorizationSha256:sha256(parentAuthorizationBytes),parentCompletedSha256:sha256(completed),consumedRequests:usage.requests,consumedReservedTokens:usage.reservedTokens,finishedRequests:usage.finishes.size,unknownUsageRequests:usage.unknownUsageRequests,unstartedTaskIds:[...taskIds],childId:protocol.id,childFrozenSha256:frozenSha256,combinedMaxRequests:usage.requests+protocol.budget.maxRequests,combinedMaxReservedTokens:usage.reservedTokens+protocol.budget.maxReservedTokens}};
}
/** Atomic parent-level consumption marker; crash/failure deliberately keeps it. */
export async function claimParentSubset(audit) {
 if(!path.isAbsolute(audit?.claimFile??'')||path.basename(audit.claimFile)!=='UNRUN_SUBSET_CLAIMED'||!audit.safe)throw Error('Parent verification: invalid claim');
 const handle=await fs.open(audit.claimFile,'wx',0o600);
 try{await handle.writeFile(JSON.stringify({...audit.safe,claimedAt:new Date().toISOString()})+'\n');await handle.sync();}finally{await handle.close();}
 return audit.safe;
}


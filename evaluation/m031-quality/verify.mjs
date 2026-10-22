import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const base=path.dirname(fileURLToPath(import.meta.url));
const normalized=text=>text.replace(/^\uFEFF/,'').replace(/\r\n/g,'\n');
export const hashText=text=>createHash('sha256').update(normalized(text)).digest('hex');
export async function verifyFreeze(directory=base) {
 const manifest=JSON.parse(await fs.readFile(path.join(directory,'frozen.json'),'utf8'));
 if(manifest.schema!=='m031-quality-freeze-v1')throw new Error('Invalid freeze');
 for(const [file,expected] of Object.entries(manifest.files))if(hashText(await fs.readFile(path.join(directory,file),'utf8'))!==expected)throw new Error('Frozen file changed: '+file);
 const protocol=JSON.parse(await fs.readFile(path.join(directory,'protocol.json'),'utf8'));
 if(hashText(await fs.readFile(path.resolve(directory,protocol.sourceMemoryProtocol.path),'utf8'))!==protocol.sourceMemoryProtocol.sha256)throw new Error('Source prospective protocol changed');
 const tasks=JSON.parse(await fs.readFile(path.join(directory,'tasks.json'),'utf8'));
 const sums={maxRequests:0,maxReservedTokens:0,maxCostMicrosUSD:0};
 for(const [name,suite]of Object.entries(protocol.suites)){
  const selected=tasks.filter(t=>t.suite===name);if(selected.length!==suite.tasks||selected.reduce((n,t)=>n+t.arms.length,0)!==suite.instances)throw new Error('Task denominator mismatch');
  if(suite.instances*suite.perInstance.maxRequests!==suite.maxRequests||suite.instances*suite.perInstance.maxReservedTokens!==suite.maxReservedTokens||suite.instances*suite.perInstance.maxCostMicrosUSD>suite.maxCostMicrosUSD)throw new Error('Invalid suite envelope');
  for(const key of Object.keys(sums))sums[key]+=suite[key];
 }
 if(Object.entries(sums).some(([k,v])=>v!==protocol.globalLimits[k]))throw new Error('Global envelope mismatch');
 if(new Set(tasks.map(t=>t.id)).size!==tasks.length||tasks.some(t=>!/^[MLT]Q31-\d{2}$/.test(t.id)))throw new Error('Invalid new task IDs');
 if(protocol.realExecutionEnabled!==false||protocol.paidCallsMade!==0)throw new Error('Review freeze cannot authorize execution');
 return {verified:true,taskCount:tasks.length,instanceCount:30,...sums,realExecutionEnabled:false,manifestSha256:hashText(await fs.readFile(path.join(directory,'frozen.json'),'utf8'))};
}
/** Offline demonstration of the mandatory exclusive marker primitive, not a live runner. */
export async function claimInstance(directory,taskId,arm) {
 const tasks=JSON.parse(await fs.readFile(path.join(base,'tasks.json'),'utf8'));if(!tasks.some(t=>t.id===taskId&&t.arms.includes(arm)))throw new Error('Unknown frozen instance');
 await fs.mkdir(directory,{recursive:true});const file=path.join(directory,`STARTED-${taskId}-${arm}.json`);
 const handle=await fs.open(file,'wx',0o600);try{await handle.writeFile(JSON.stringify({schema:'m031-offline-marker-demonstration-v1',taskId,arm,createdAt:new Date().toISOString(),networkRequests:0}));await handle.sync();}finally{await handle.close();}return file;
}
export function assertLiveLaunch(){throw new Error('NOT_AUTHORIZED: live runner, final profile/source/pricing bindings and explicit approval are missing');}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))console.log(JSON.stringify(await verifyFreeze(),null,2));

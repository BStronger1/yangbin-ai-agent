import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export async function verifyFrozen(here, runtimeDir) {
  const bytes = await fs.readFile(path.join(here,'frozen.json'));
  const frozen = JSON.parse(bytes);
  for (const [relative, digest] of Object.entries(frozen.files)) {
    if (path.isAbsolute(relative) || relative.split('/').includes('..')) throw Error('Unsafe frozen file');
    if (sha256(await fs.readFile(path.join(here,relative))) !== digest) throw Error('Frozen evaluator changed');
  }
  for (const [relative,digest] of Object.entries(frozen.runtime)) {
    if (path.isAbsolute(relative) || relative.split('/').includes('..')) throw Error('Unsafe runtime file');
    if (sha256(await fs.readFile(path.join(runtimeDir,relative))) !== digest) throw Error('Frozen runtime changed');
  }
  if(sha256(await fs.readFile(path.join(here,'../memory-m029/core.mjs')))!==frozen.legacyCoreSha256)throw Error('Frozen legacy fixture helper changed');
  const pi=JSON.parse(await fs.readFile(path.join(runtimeDir,'node_modules/@earendil-works/pi-coding-agent/package.json')));
  if(pi.version!==frozen.piVersion)throw Error('Installed pi differs');
  return {frozen,sha256:sha256(bytes)};
}
export async function claimAuthorization({authorizationPath,runDirectory,protocol,frozenSha256,endpointSha256}) {
  if(!authorizationPath||!path.isAbsolute(authorizationPath))throw Error('Approved authorization file required');
  const approval=JSON.parse(await fs.readFile(authorizationPath,'utf8'));
  if(approval.status!=='APPROVED'||approval.batchId!==protocol.id||approval.frozenSha256!==frozenSha256)throw Error('Authorization missing or mismatched');
  if(typeof endpointSha256!=='string'||approval.endpointSha256!==endpointSha256)throw Error('Authorization endpoint mismatch');
  if(!path.isAbsolute(approval.runDirectory)||path.resolve(approval.runDirectory)!==path.resolve(runDirectory))throw Error('Run directory differs from authorization');
  if(!path.isAbsolute(approval.ledgerDirectory)||approval.maxRequests!==protocol.budget.maxRequests||approval.maxReservedTokens!==protocol.budget.maxReservedTokens)throw Error('Authorization budget or ledger mismatch');
  await fs.mkdir(approval.ledgerDirectory,{recursive:true});
  const ledger=path.join(approval.ledgerDirectory,protocol.id+'.STARTED');
  // An administrator-owned approval binds a single batch, ledger and run path.
  // Changing only --run-dir cannot bypass this crash-retained global marker.
  const handle=await fs.open(ledger,'wx',0o600);
  try {await handle.writeFile(JSON.stringify({batchId:protocol.id,frozenSha256,runDirectory,startedAt:new Date().toISOString()}));}finally{await handle.close();}
  return ledger;
}
export function failureCategory(error) {
  const s=String(error?.message??'');
  if(s.includes('generation hit the token cap'))return 'summary_output_cap';
  if(s.includes('SDK output ceiling'))return 'invalid_output_budget';
  if(s.includes('empty summary'))return 'empty_summary';
  return 'runtime_error';
}
export function observeResponse(response,record) {
  record.httpStatus=response.status;record.finishReasons=[];record.thinkingChars=0;record.visibleChars=0;
  if(!response.body)return response;
  let buffer='';const decoder=new TextDecoder();
  const line=s=>{if(!s.startsWith('data:'))return;let item;try{item=JSON.parse(s.slice(5));}catch{return;}
    for(const c of item.choices??[]){if(c.finish_reason&&!record.finishReasons.includes(c.finish_reason))record.finishReasons.push(['stop','length','tool_calls','content_filter','function_call'].includes(c.finish_reason)?c.finish_reason:'other');
      const d=c.delta??{};if(typeof d.content==='string')record.visibleChars+=d.content.length;
      for(const k of ['reasoning_content','reasoning','reasoning_text'])if(typeof d[k]==='string')record.thinkingChars+=d[k].length;
    }
    if(item.usage){record.inputTokens=Number.isSafeInteger(item.usage.prompt_tokens)?item.usage.prompt_tokens:null;record.outputTokens=Number.isSafeInteger(item.usage.completion_tokens)?item.usage.completion_tokens:null;}
  };
  return new Response(response.body.pipeThrough(new TransformStream({transform(chunk,controller){buffer+=decoder.decode(chunk,{stream:true});let i;while((i=buffer.indexOf('\n'))>=0){line(buffer.slice(0,i));buffer=buffer.slice(i+1);}if(buffer.length>1048576)throw Error('Observed SSE too large');controller.enqueue(chunk);},flush(){buffer+=decoder.decode();if(buffer)line(buffer);}})),{status:response.status,statusText:response.statusText,headers:response.headers});
}

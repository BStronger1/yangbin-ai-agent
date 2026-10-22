import fs from 'node:fs/promises';
import path from 'node:path';
import {BudgetLedger,TaskStore,assertIdentity} from './contracts.mjs';
import {createTeamRuntime} from '../teams/runtime.mjs';
import {loadRemoteRegistry} from '../a2a/registry.mjs';
import {createTeamExecutorRouter} from '../a2a/team-executor.mjs';
import {createLocalExecutor} from '../teams/local-executor.mjs';
export async function runTeamEntry(input,options,env,runSingleAgent){
 const {emit,signal,onGate,onTools,onTeam}=options;
 const identity=assertIdentity(input.execution?.identity);
 if(input.prompt.length>10000)throw new Error('团队任务说明超过10000字，请拆分任务；没有启动子Agent。');
 const sessionDir=path.resolve(input.sessionDir),workspace=await fs.realpath(input.workspace);
 if(sessionDir===workspace||sessionDir.startsWith(workspace+path.sep))throw new Error('Team state must be outside workspace');
 await fs.mkdir(sessionDir,{recursive:true});
 const ledger=new BudgetLedger({maxRequests:Math.min(60,input.execution?.limits?.maxRequests||60),maxTokens:Math.min(1000000,input.execution?.limits?.maxTokens||1000000),journal:path.join(sessionDir,`budget-${identity.taskId}.jsonl`)});
 const store=new TaskStore({emit});
 const local=createLocalExecutor({runAgent:runSingleAgent,env,approve:null,approvalMode:input.approvalMode||'on-risk',onGate,onTools,budgetManagedByRunAgent:true,onEvent:(event,child)=>{
  if(['model_request','model_usage','approval_required','approval_resolved'].includes(event.type))emit({...event,childTaskId:child.taskId,agentId:child.agentId});
 }});
 const registry=await loadRemoteRegistry({file:env.CODING_A2A_CONFIG,workspace,identity,emit,onGate,onTools});
 const executor=createTeamExecutorRouter({local,resolveRemote:registry.resolveRemote});
 const runtime=createTeamRuntime({store,ledger,executor,rootDirectory:path.join(sessionDir,'teams'),emit,maxConcurrency:2,maxCorrections:1,timeoutMs:300000});
 const permissions=['list','read','write','edit'];
 const parallel=/并行|扇出|parallel|fan.?out/i.test(input.prompt);
 let members=[
  {id:'manager',role:'manager',prompt:'拆解下面的任务，给执行者具体步骤、验收条件及未知边界；不要声称已执行。任务：'+input.prompt,permissions:['list','read'],dependsOn:[],writePaths:[]},
  {id:'executor',role:'worker',executorId:registry.workerId,prompt:'阅读管理者产物，完成下列任务并把可交付结果写入result.md。当前为隔离暂存工作区；不得声称已经修改用户主项目或执行终端。任务：'+input.prompt,permissions,dependsOn:['manager'],writePaths:['result.md']},
  {id:'reviewer',role:'reviewer',prompt:'独立核验实际结果是否满足任务，不以执行者自称成功为依据。任务：'+input.prompt,permissions:['list','read'],dependsOn:['executor'],writePaths:[],reviewOf:'executor'}
 ];
 if(parallel){
  const manager={...members[0],prompt:'为分析执行者和核验执行者拆分任务，给出各自工作、交付内容和汇总验收条件。角色权限与文件由服务器固定。任务：'+input.prompt};
  const branch=(id,file,focus)=>({id,role:'worker',executorId:'local',prompt:`阅读管理者产物，${focus}，完成任务后把结果写入${file}。这是隔离暂存区，不能声明已修改主项目。任务：${input.prompt}`,permissions,dependsOn:['manager'],writePaths:[file]});
  const aggregate={...members[1],id:'aggregate',prompt:'阅读所有上游产物，综合两位执行者的结果；核对分歧并把最终可交付结果写入result.md。任务：'+input.prompt,dependsOn:['analysis','verification']};
  members=[manager,branch('analysis','analysis.md','完成主要分析与实现说明'),branch('verification','verification.md','独立寻找反例、缺失信息与验证条件'),aggregate,{...members[2],dependsOn:['aggregate'],reviewOf:'aggregate'}];
 }
 emit({type:'strategy',mode:'team',reason:parallel?'启动管理者、两个并行执行者、汇总者和独立审查者；隔离暂存，最多一次修正。':'启动管理者、执行者和独立审查者；各有隔离上下文，产物先暂存，最多一次修正。'});
 onTeam?.({cancel:taskId=>store.cancel(identity,taskId)});
 try{
  const result=await runtime.execute({identity,permissions,members,signal});
  await fs.writeFile(path.join(sessionDir,`team-results-${identity.taskId}.json`),JSON.stringify(result),{flag:'wx',mode:0o600});
  const artifacts=result.artifacts.map(({internalPath,...a})=>a);
  emit({type:'team',event:'result',status:result.status,teamTaskId:identity.taskId,artifacts,budget:result.budget});
  emit({type:'text_delta',delta:`团队执行状态：${result.status}。已运行${result.members.length}个独立角色；产物在团队面板中查看。结果暂存，未覆盖主项目文件。`});
  emit({type:'status',status:result.status});
 }finally{onTeam?.(null);}
}

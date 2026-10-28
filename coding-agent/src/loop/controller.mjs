import {createHash} from 'node:crypto';
const ordered=value=>Array.isArray(value)?value.map(ordered):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(k=>[k,ordered(value[k])])):value;
const MODES=new Set(['auto','direct','react','plan','check','team']);
export function selectStrategy({mode='auto',prompt='',toolsAvailable=true,teamAvailable=false}) {
 if(!MODES.has(mode))throw new Error('Unknown execution mode');
 if(typeof prompt!=='string'||prompt.length>32000)throw new Error('Invalid strategy prompt');
 let chosen=mode,reason='使用你指定的执行方式。';
 if(mode==='auto'){
  if(/多智能体|多代理|多个.*[Aa]gent|agent\s*team|multi.?agent/i.test(prompt)){chosen='team';reason='任务明确要求多智能体协作。';}
  else if(/实现|修改|修复|重构|开发|部署|制作|启动|运行|游戏|文件|仓库|测试|implement|refactor|debug|file|repository|read|write|edit|create|test|fix|build/i.test(prompt)){chosen=prompt.length>350||/并且|然后|分阶段|集成|重构|规划|and then/i.test(prompt)?'plan':'react';reason=chosen==='plan'?'任务包含多步操作，先给出简短计划，再按工具证据执行。':'任务需要工具证据，按观察结果逐步执行。';}
  else{chosen='direct';reason='当前任务可直接回答，无需计划或反思循环。';}
 }
 if(chosen==='team'&&!teamAvailable)throw new Error('团队执行器尚未启用，未启动任何子 Agent。');
 if(chosen!=='direct'&&!toolsAvailable&&chosen!=='team')throw new Error('当前授权没有所需工具。');
 return Object.freeze({mode:chosen,reason,toolsEnabled:chosen!=='direct'});
}
export class LoopController {
 constructor({strategy,maxSteps=40,maxMs=300000,maxReflections=2,maxNoProgress=3,signal,emit=()=>{},clock=()=>Date.now()}){
  if(!strategy||!MODES.has(strategy.mode)||strategy.mode==='auto')throw new Error('Invalid strategy');
  for(const n of [maxSteps,maxMs,maxNoProgress])if(!Number.isSafeInteger(n)||n<1)throw new Error('Invalid loop limit');
  if(!Number.isSafeInteger(maxReflections)||maxReflections<0||maxReflections>5)throw new Error('Invalid reflection limit');
  this.strategy=strategy;this.limits={maxSteps,maxMs,maxReflections,maxNoProgress};this.steps=0;this.reflections=0;this.noProgress=0;this.last=null;this.signal=signal;this.emit=emit;this.clock=clock;this.start=clock();this.calls=new Set();this.pending=new Set();this.failed=false;
 }
 check(){this.signal?.throwIfAborted();if(this.failed)throw new Error('Loop has stopped');if(this.clock()-this.start>=this.limits.maxMs)throw new Error('Loop time limit reached');}
 beforeModel(){this.check();if(++this.steps>this.limits.maxSteps)throw new Error('Loop step limit reached');}
 beforeTool({callId,name,args,sideEffect=false}){this.check();if(this.strategy.mode==='direct')throw new Error('Direct mode does not allow tools');if(this.calls.has(callId))throw new Error('Tool call must not be replayed');const fingerprint=createHash('sha256').update(JSON.stringify([name,ordered(args)])).digest('hex');if(sideEffect&&this.pending.has(fingerprint))throw new Error('Unknown external effect must not be replayed');this.calls.add(callId);if(sideEffect)this.pending.add(fingerprint);return fingerprint;}
 afterTool({fingerprint,result,sideEffect=false,known=false,ok=false}){this.check();if(sideEffect&&known)this.pending.delete(fingerprint);const current=createHash('sha256').update(JSON.stringify([fingerprint,result])).digest('hex');this.noProgress=current===this.last?this.noProgress+1:0;this.last=current;if(this.noProgress>=this.limits.maxNoProgress){this.failed=true;throw new Error('No progress limit reached');}if(!ok&&this.strategy.mode!=='direct'&&this.strategy.mode!=='team'){this.reflect({externalFeedback:JSON.stringify(result)});this.strategy={...this.strategy,mode:'check',reason:'工具报告失败，按外部反馈检查并有限修正。'};this.emit({type:'strategy',...this.strategy,steps:this.steps});}}
 reflect({externalFeedback}){this.check();if(typeof externalFeedback!=='string'||!externalFeedback.trim())throw new Error('External feedback required');if(++this.reflections>this.limits.maxReflections)throw new Error('Reflection limit reached');return {instruction:'依据以下外部工具/测试反馈检查问题。不要自称测试通过；最多执行必要修正。',feedback:externalFeedback.slice(0,4096)};}
 summaryFailed(){this.failed=true;this.emit({type:'strategy',mode:this.strategy.mode,status:'STOPPED',reason:'摘要失败，已停止；保留原始会话，不自动重放。'});}
 instruction(){return {direct:'直接回答用户问题，不调用工具，不启动规划或反思。',react:'根据工具观察逐步执行。失败时先核对实际结果，禁止重放未知副作用。',plan:'先给出简短可检验计划，再执行。最终报告工具/测试证据和未完成项，不输出隐藏思维链。',check:'依据工具或测试的实际反馈检查与有限修正；自评不代表验收通过。',team:'通过真实团队执行器委派并汇总独立子任务。'}[this.strategy.mode];}
}

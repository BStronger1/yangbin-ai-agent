# M031 integration contract v1

共同快照：2f7a8d306638face8970a97152c709ff5f0c7193；既有成果非本轮新增。所有专项从该提交出发，integration按专项提交cherry-pick。原工作区只读，私有交接与旧真实评测证据不复制。

| 所有者 | 专属文件 | 依赖 |
|---|---|---|
| integration | runtime/contracts.mjs及测试、agent.mjs/main.mjs/ModelGate、Java CodingRuntime/NodeCodingRuntime/CodingService/controllers、API/router/CI/锁文件/部署 | 全部 |
| memory | MemoryService/MemoryCandidateExtractor、独立测试、新offline诊断、M031-MEMORY | 原M029生命周期 |
| mcp | coding-agent/src/mcp、test/mcp*、M031-MCP | trusted identity/authorize/reserve/settle |
| a2a | coding-agent/src/a2a、test/a2a*、M031-A2A | 同一TaskStore/Executor |
| loop | coding-agent/src/loop、test/loop*、M031-LOOP | pi扩展、统一BudgetLedger |
| ui | composables、弹层相关Vue、overlays.browser、M031-UI | 现有后端审批语义 |
| teams | coding-agent/src/teams、test/teams*、M031-TEAMS | 同一TaskStore/BudgetLedger/Executor |

## 服务端上下文

Identity={accountId,projectId,sessionId,taskId,agentId,parentTaskId,parentAgentId}。认证服务/父任务生成；模型、外部工具、记忆均无权设置。子任务沿用账号/项目/会话，生成新的task/agent，permissions只能是父授权的子集。

Task={identity,status,revision,progress,artifacts,error}。状态QUEUED/RUNNING/WAITING_APPROVAL/BLOCKED/SUCCEEDED/FAILED/CANCELLED/PARTIAL/INTERRUPTED；终态不可恢复；重启不自动重放。事件含taskId、agentId、parentTaskId、seq、createdAt及内容白名单。不记录隐藏思维。

Artifact={id,taskId,kind,name,sha256,size}；由服务端映射本地存储，外部URL不自动下载；访问必须account/project均匹配。

## 统一预算与审批

BudgetLedger为一次根执行的唯一预算实例，所有子任务、摘要、反思、重试同实例；进程内预留为同步原子操作，生产另受Java账号全局持久治理准入。reserve({identity,kind,requestId,reservedTokens,maxCostMicros})返回reservationId；预留先于网络，requestId一次性，未明usage保持占用。settle({reservationId,usage,status}) usage={inputTokens,outputTokens,costMicros}或null，未知费用单列；禁止将SDK零默认视作真实免费。多进程不得各建独立同预算账本绕过父治理。

authorize({identity,toolName,definitionHash,argumentsHash,callId,signal})返回{approved,approvalId}，远端工具均需可信授权；审批绑定完整定义/参数，变更失效。非幂等或结果未知不自动重放。模型不可通过delegation改变授权。

Executor.execute({identity,prompt,permissions,signal,budget,emit,workspace,context})返回{status,artifacts,error,evidence}；独立上下文，workspace写所有权由编排器排他控制。远端Executor只能适配相同身份/任务/预算，不能自建平行权限体系。

Memory候选独立于active items；仅统一MemoryService确认可写，document/candidate/item版本同时校验。来源类型与不确定性保留；模型反思不是用户事实。远端文本/工具描述/记忆均不可信。

## 验收

仅离线/本地合成服务；各worktree独立测试目录及临时端口。真实模型评测需新冻结预算并单独批准。本轮未授权费用。只有integration可部署，须先整体验收/回滚/数据兼容检查；终端开关保持false。

# 工作空间与执行边界

M026 产品约定：顶层是「通用助手 / Coding Agent」，通用助手包含 Chat 与 Work。Work 不再作为 Coding 的产品名称。入口表达用户意图，不限定模型只能处理某类内容，也不扩大工具权限。

## 可审阅的工作流程

Chat 优先直接回应，不要求填写复杂任务简报；上传材料时复用现有解析能力。Work 先明确目标、交付物和约束，由用户确认执行，再展示实际材料分析、模型调用、成果保存等阶段。阶段来自服务执行事件，不用计时动画冒充进度。成果可预览与下载，迭代沿用同一会话但需明确本轮要求。

Coding 使用既有 pi SDK 适配层、账号私有项目、文件审批、变更差异和执行状态。命令终端、Git/worktree、steer/followUp 与产品内部子智能体，只有实现并单独验收后才可开放；目前不作为可用入口展示。开发时多智能体协作不是产品内多 Agent 能力。

## 路由兼容

| 入口 | 归属与兼容规则 |
| --- | --- |
| `/assistant?mode=chat` | 新通用 Chat |
| `/assistant?mode=work` | 新通用 Work |
| `/coding` | Coding Agent |
| `/work`、`/work?mode=work`、旧 Coding session 链接 | 保持 Coding 上下文，不解释为新的通用 Work |
| `/work?mode=chat`、`/super-agent` 与旧 chat 编号 | 保留原 Manus 对话与工具上下文 |
| `/work/:skillId`、`/partners/:partnerId` | 原技能任务和伙伴会话保留，不重置历史 |

会话 ID 属于原执行适配器；迁移入口不移动或重写用户记录。项目关联以服务端会话为准。新助手会话的 mode 创建后不可更改；旧伙伴记录缺省 mode 为 legacy，不自动认领到新空间。

## 共享边界与持久状态

账号、模型配置与凭据保护、权限、模型准入、请求与 Token 配额共用既有服务。新助手复用 PartnerChat 的持久任务、材料和成果模块；Coding 复用现有 pi 适配器。新助手提交纳入同一网关限流，切换 Chat/Work/Coding 不增加配额。

服务端状态是执行事实来源。新助手先 POST 一个带 requestId/revision 的任务，再通过只读 SSE `state` 快照或 GET 读取；订阅不拥有任务生命周期，断线/切换只释放订阅。SSE 是状态事件，并非逐 Token 流。未知提交结果先查询同一编号，不重新生成编号或自动重试付费请求。取消只通过显式操作；已发送供应商请求不保证可撤回，取消后禁止迟到回复发布新成果。

浏览器按账号、空间与具体会话保存模型选择、草稿和布局；跨账号不复用缓存。跨空间只通过用户显式审阅后生成草稿，不能自动注入整个普通聊天或把材料引用当作授权。

新 Chat/Work 当前使用提供的文字和已解析材料；伙伴、技能、知识库、MCP 工作台保持可访问。MCP 结果须用户显式选择引用，不宣称新助手会自主调用任意连接器；PPT仍使用现有独立制作入口。

## 接口约定

`/api/ai/assistant/partners/{partnerId}` 默认使用 office 的现有材料存储与管理策略。所有助手操作携带 `mode=chat|work`；任务 body mode 与查询 mode 一致。Work body 还需要 `brief={goal,deliverable,constraints}` 和 `confirmed=true`，确认信息冻结在本轮，不能给额外权限。Chat 直接回复且不自动生成工作成果文件。

- GET `/conversations?mode=...`：本空间历史。
- GET `/conversations/{id}/page?mode=...`：真实持久状态与分页内容。
- GET `/conversations/{id}/events?mode=...`：只读快照 SSE；连接受限且可重新读取同一任务。
- POST `/jobs?mode=...`：去重提交。
- POST `/conversations/{id}/stop?mode=...`：明确停止。

材料上传/下载与共享模型管理继续使用原有账号保护接口，不新建第二套密钥库。

## 参考与来源

产品交互参考 [OpenAI 的 Chat / Work / Codex 使用说明](https://learn.chatgpt.com/docs/use-chatgpt)与 [Work 入门](https://learn.chatgpt.com/docs/get-started-with-work)，仅借鉴公开的用户流程，不推断未公开架构，不复制品牌。

只读研究 [pi-gui](https://github.com/minghinmatthewlam/pi-gui) 的 README、AGENTS、架构与接口：session-driver 定义会话/事件合同，pi-sdk-driver 委托上游 pi，Review/checkpoint/worktree 归 desktop owners/platform。其本地 Electron 文件、凭据和进程权限不能照搬到公网多账号服务。本轮没有复制该项目源码；仅借鉴持久状态与订阅生命周期分离。上游 MIT 许可及既有 pi 归属见仓库 NOTICE/LICENSING 和 coding-agent/PI-LICENSE。

上游已有 steer/followUp、按轮次快照 Review、worktree 与子线程；本项目已有 pi 文件工具、审批、持久事件和最近任务差异。上游上述扩展不代表本项目已经接入。M026 不重写 pi Agent Loop。

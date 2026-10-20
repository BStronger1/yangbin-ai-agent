# yangbin Coding Agent runtime

使用用户指定参考 `yangbin-ai-code-mother/codingAgent/pi` 的公开 SDK 集成方式：`createAgentSession`、自定义工具、`SessionManager` 与事件订阅。固定依赖 `@earendil-works/pi-coding-agent` 1.3.0（MIT，Mario Zechner / earendil-works）；pi-gui 作为会话驱动拆分参考。这里是项目新增的 Java↔Node 适配及权限工具层，不声称从零实现 pi。

## 启动

需要 Node >=22.19。在本目录运行 `npm ci --ignore-scripts`。服务端设置专用环境变量 `CODING_MODEL_API_KEY`、`CODING_MODEL_BASE_URL`、`CODING_MODEL_NAME`（兼容 `CODING_MODEL_MODEL`），通过 `CODING_MODEL_API` 指定支持工具调用的原生 Claude Messages 或 OpenAI-compatible Chat Completions 服务。凭据仅从进程环境传入，不落盘、不向浏览器返回。`node src/main.mjs --check` 检测 Node/SDK 及实际隔离能力，不访问模型。Java 生产启动强制 `CODING_GOVERNANCE_REQUIRED=true`。

由 Java 服务每轮启动 `node src/main.mjs`，stdin/stdout 均为 JSONL：

```json
{"type":"start","id":"turn-id","workspace":"absolute-owned-workspace","sessionDir":"absolute-private-session-dir","prompt":"任务","approvalMode":"on-risk","allowShell":false}
{"type":"approve","id":"ack-id","approvalId":"event-approval-id","approved":true}
{"type":"cancel","id":"cancel-id"}
```

输出为 `ready`、`event`（事件体在 `event` 字段）与 `response`。`response.id` 对应命令 id；start 的 response 表示该轮终结，不能把审批 ack 当成任务完成。`event.type` 为 `status/text_delta/tool_start/tool_end/approval_required/approval_resolved/error/sandbox/context/model_request/model_usage`。状态为 RUNNING/SUCCEEDED/FAILED/CANCELLED；Java 维护持久展示事件与审批身份。会话数据单独保存在 sessionDir，工具不能访问；每轮 `continueRecent` 恢复该目录会话，账号与任务目录由 Java 确定。

## 执行边界

- 默认只注册 `list/read/write/edit`，所有这些工具都是本实现。pi 内置同名工具被明确替换；没有额外的内置 shell、扩展、MCP、技能、全局用户配置或工作区代码自动加载。
- 文件只接受 `/` 分隔的相对路径，拒绝越界、符号链接/junction、硬链接、Windows设备名和尾点/空格；禁止 `.env*`、`.git`、`.ssh`、`.aws`、`.codex`、`.pi`、认证命名文件和密钥后缀。单文件/输出最多64KiB，目录最多1000项，每轮最多80次工具请求，超过即中断整个pi会话。新目录按需创建；write覆盖原文件，edit要求唯一匹配。
- `ask` 每次工具均审批；`on-risk` 允许读取/列目录，修改文件和shell逐次审批；`allow` 不进行单次询问。审批5分钟超时拒绝，仅对当次approvalId有效。批准、拒绝、过期、取消都会发出approval_resolved终态（approved/rejected/expired/cancelled）；失败的授权另发tool_end，不保留可点击的失效审批。
- `bash` 仅在 Java 判定管理员、显式开启配置、Linux `bwrap` + `prlimit` 实际能力探测通过时注册。管理员可用服务端环境 `CODING_BWRAP` 指向项目私有安装的绝对可执行路径（默认 `/usr/bin/bwrap`），请求参数不能覆盖。缺组件/必需参数、用户命名空间受限、Windows 等均明确禁用，**不会回落到宿主 shell**。独立网络/PID/IPC/UTS 等命名空间、非 root UID、清洁环境，宿主 `/home`、`/etc`、`/run`、`/sys` 不挂载。系统可执行文件及运行库只读，`/usr/local` 隐藏；根目录、`/usr/local`、`/dev`、`/proc` 显式只读重挂，只有受限 `/tmp` 可创建临时文件。能力探测也实际验证这些写入拒绝。
- 命令看到的是**只读工作区快照**，过滤凭据和元数据、拒绝链接/特殊文件；快照最多 32 MiB、1000 文件、2000 目录项、20 层。shell 用于验证，修改通过审批后的 write/edit 工具进行。临时空间 64 MiB、单文件 16 MiB、单进程地址空间 1 GiB、文件描述符128、同真实UID进程限制64，最长120秒，输出64KiB，取消终止进程组。这些不是 cgroup 聚合 CPU/内存配额，不支持依赖联网安装；隔离功能必须在目标 Linux 主机实际验收。
- 不自动重试模型；启用 pi 原生上下文阈值压缩。默认上下文窗口64000（管理员可用 `CODING_CONTEXT_WINDOW` 限定4096–200000），预留输出与近期消息，压缩结果由pi持久化；`context` 事件展示恢复消息数、压缩状态及压缩前估计量，不暴露摘要正文。压缩也是模型请求，经过相同额度准入，失败不伪造已压缩。摘要质量仍受模型影响，不等于长期语义记忆。
- 每轮硬超时10分钟、最多100次模型HTTP请求（含压缩）；取消发送 pi.abort 并关闭待审批/子命令，不能保证服务商停止已发生的计费。执行过的文件操作不会因取消而回滚。会话目录与事件存储均为单实例文件方案。
- 文件工具边界针对受控串行执行；不能抵御具有相同OS权限的外部进程并发篡改目录。真实多租户不可信代码执行需要OS级隔离。

## 验证

`npm test` 使用 Node 自带测试器，无付费模型调用。包括真实文件读写编辑、路径/链接保护、审批拒绝/批准/取消和缺隔离时拒绝shell。集成用真实安装的pi SDK连接loopback假OpenAI SSE服务，经过write→read→edit→最终答复，并启动第二进程核对先前会话消息恢复；没有mock SDK。结果证明SDK/协议/工具链集成，不是模型任务成功率或线上验收。




## BYOK 多厂商协议（M016）

- `CODING_MODEL_API` 仅允许 `openai-completions`（默认，保留旧配置）或 `anthropic-messages`。Claude 使用原生 Messages 与 `x-api-key`；OpenAI/DeepSeek/Kimi 使用兼容 Chat Completions 与 Bearer。自定义模型名保留原值。
- `CODING_MODEL_BASE_URL` 是完整 API 前缀，运行时不补 `/v1`。OpenAI 例如 `https://api.openai.com/v1`；Claude 例如 `https://api.anthropic.com`（SDK追加 `/v1/messages`，当前SDK也带beta查询）；DeepSeek例如 `https://api.deepseek.com`；Kimi例如 `https://api.moonshot.ai/v1`。代理的已有provider路径完整保留。
- `CODING_MODEL_PROVIDER=deepseek|kimi` 开启当前pi SDK真实支持的reasoning_content回传、工具消息空content和max_tokens兼容；不强制temperature、thinking或reasoning_effort，保持服务商默认。其他自定义服务不自动推断具备全部兼容能力。
- 普通账号模型端点必须HTTPS且解析到公网地址。`CODING_MODEL_ALLOW_PRIVATE=true`仅由Java对管理员设置，用于私有模型服务或本地测试。Node在每次实际建连的DNS lookup中校验全部解析地址，返回校验后的IP给连接器；拒绝IPv4/IPv6私网、回环、链路本地和保留地址。所有HTTP重定向一律拒绝，不把密钥转发给重定向目的地；fetch请求也限制同origin。
- 认证只在当前进程内传入。上游错误响应先脱敏再交SDK；SDK message_end事件在pi落盘之前原地脱敏；文本流跨delta保留可能的密钥前缀，避免分片回显泄漏；工具参数与协议/诊断也脱敏。脱敏针对配置密钥的原值和URL/JSON编码，不声称识别任意编码/拆分隐写或用户文件里的未知秘密。
- 测试使用真实安装pi SDK + loopback模拟OpenAI/Anthropic SSE，涵盖原生工具循环、上下文恢复、三种baseURL前缀、自定义model、DeepSeek/Kimi推理字段回传、401回显/成功流分片回显脱敏、重定向密钥保护、DNS建连边界。全部都是协议/安全本地测试，未调用真实厂商，不代表模型生成质量或服务商账号验证。

## 每次模型请求的额度协议

生产Java运行器强制启用额度门控。Node在实际HTTP请求（包括压缩）前发出 `model_request`，字段 `requestId`、`reservedTokens`；预留按完整请求UTF-8字节数加输出上限保守估计，不当作精确token数。Java将统一的 `app.governance.output-tokens`（默认4096）通过可信 `CODING_OUTPUT_TOKENS` 传入，并在普通及压缩请求的实际SDK调用处收紧输出上限；用户请求参数不能覆盖。Java需回复 `{"type":"model_permission","requestId":"…","approved":true}`，10秒无授权或拒绝则不发出HTTP。单独运行Node默认不启用门控，仅供本地协议测试。

流式结束后 `model_usage` 带相同编号、`inputTokens`、`outputTokens`、`status`（SUCCEEDED/FAILED/UNKNOWN）。OpenAI读取真实usage字段，Anthropic输入包含已报告的缓存读取/写入；没有报告的用量为null，不拿SDK填充0推断免费。未收到终止标记就断流或取消时为未知；SDK在正常终止标记后关闭流仍为SUCCEEDED，即使供应商未报告用量。Java保守保留预留额度；费用需模型定价才能计算。pi模型注册中的零成本字段只满足SDK模型结构，不作为账单数据。

新增模拟覆盖真实JSONL授权/拒绝、OpenAI及Anthropic用量、未知用量、拒绝前零HTTP，以及真实pi跨26轮恢复和实际触发压缩。Linux沙箱烟测只有能力探测通过才执行，否则明确跳过；本地Windows验证仅能证明安全拒绝路径，不能声称Linux沙箱已验收。

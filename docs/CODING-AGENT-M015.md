# Coding Agent M015：双模式入口与 pi 执行链

> 后续状态：2026-10-18已发布M015，见[上线验收M016](DEPLOYMENT-M016.md)。下文保留本地开发轮次记录，“未部署”和零真实供应商请求仅指该开发轮次。

日期：2026-10-18。本轮为本地开发与离线验证，未部署、未提交、未推送。线上最后已知发布仍是 `m014-20261017-ef8f471051c7`，本轮没有重新探测线上健康。

## 用户目标与当前交付

“新工作任务”及首页采用用户参考图的浅色布局：中央欢迎语、对话/工作切换、底部固定输入框、项目与审批选择。对话进入既有 AI 超级智能体 `/ai/manus/chat`；工作进入独立 Coding Agent。`/work/:skillId`、伙伴、技能、PPT和出行页面保留。

Coding Agent 使用实际安装的 `@earendil-works/pi-coding-agent` 1.3.0，提供真实工具循环与会话恢复。本项目新增 Java 账号/项目/任务管理、JSONL 子进程适配、自定义受控工具及网页操作界面；不是从零实现 pi，也不是 pi-gui 桌面应用的完整迁移。上游声明见 `coding-agent/PI-LICENSE` 与 `NOTICE.md`。

已接通：账号私有项目创建；项目文本文件查看/新建/编辑；编码任务连续轮次；实时文本增量与工具事件的轮询展示；逐操作审批/拒绝；取消；刷新与下一轮进程恢复；最近一轮执行前后的文本差异。`list/read/write/edit` 均执行实际文件操作；管理员显式启用后可注册 `bash`（Windows用PowerShell，Linux用bash）。

## 结构与入口

- 前端：`yangbin-ai-agent-frontend/src/views/CodingWorkspace.vue`、`src/api/coding.js`、`src/coding-workspace.css`；路由和 `WorkspaceNav.vue`。
- Java：`src/main/java/com/yangbin/yangbinaiagent/coding/` 与 `controller/CodingController.java`。
- pi：`coding-agent/src/main.mjs`（JSONL），`agent.mjs`（SDK/会话），`tools.mjs`（文件/审批/命令）。
- 状态：`tmp/coding/<owner>/projects/<id>/workspace` 存项目文件；`sessions/<id>.json` 存网页事件；`runtime/<id>` 存私有 pi 会话及最近轮次基线。工具工作目录不能读会话存储。
- API前缀 `/api/ai/coding`：capabilities、projects、projects/{id}/files/file、sessions、sessions/{id}/turns/approvals/{approvalId}/cancel/diff。请求经过原账号代理和内部身份校验，服务端从 `UserContext` 取owner，不接受浏览器指定owner或绝对工作路径。

## 审批与执行契约

| 模式 | 行为 |
|---|---|
| 始终询问 `ask` | 每次工具调用前确认 |
| 按需确认 `on-risk` | 列目录/读取自动；写入、编辑、命令逐次确认 |
| 全部允许 `allow` | 在已授予的工具权限内自动执行；不能绕过服务端shell开关 |

审批只对应当前轮次的具体操作，5分钟超时拒绝。人工处理与运行时失效事件去重。请求ID与内容绑定、轮次版本检查避免重复模型提交；一个账号同一时间只运行一个编码worker。取消会保留已执行文件更改，旧进程退出前阻止再次写入/启动；重启把活动任务标记为中断，不自动重新调用模型，也不自动恢复旧审批。

文件工具拒绝越界、符号链接/junction、硬链接、Windows设备名与路径别名；禁止 `.env*`、`.git`、`.ssh` 等配置目录与凭据命名文件。pi全局扩展/skills/工作区代码自动发现已关闭。模型凭据只进入Node专用环境，命令子进程再次剥离这些环境字段，接口不返回密钥。

## 启用方式

安装 Node >=22.19；系统默认Node20不能运行此版本SDK。本轮使用桌面内置Node24.19.0及JDK22，源码release21。

```powershell
# 在项目根目录，确保 PATH 中为兼容 Node
npm --prefix coding-agent ci --ignore-scripts
node coding-agent/src/main.mjs --check
$env:CODING_ENABLED='true'
$env:CODING_NODE='node'
# 沿用既有模型配置；如需专用模型，设置 CODING_MODEL_API_KEY/BASE_URL/NAME。
# 仅受信管理员需要命令功能时才显式设 CODING_SHELL_ENABLED=true。
```

然后按既有账号代理与后端/前端启动流程运行。`CODING_ENTRY` 默认相对后端工作目录 `coding-agent/src/main.mjs`，可设绝对路径。`app.coding.dir` 默认 `tmp/coding`，也可通过Spring参数指定。模型需支持 OpenAI-compatible Chat Completions 工具调用；未启用、模型不齐或SDK检查失败时，网页显示具体未就绪状态并禁发编码任务。运行环境准备和本地假模型成功不代表任何真实模型质量已经验收。

本轮没有更改线上部署脚本去自动启用Node；未来部署必须一起打包并安装 `coding-agent`、配置Node路径、备份 `tmp/coding`，再用正常登录完成线上验收，不能仅替换Java JAR就宣称已上线。

## 验证证据

最终数字见本节后续“本轮结果”和 `intern/tmp/coding-agent-m015-evidence/final-verification.json`。测试只连接loopback模拟模型，未读取真实模型凭据，真实供应商请求0。

- `CodingAgentEndToEndTest`：实际HTTP鉴权 → Java → JSONL新Node进程 → 安装的pi SDK → 本地模拟OpenAI SSE → 实际文件。检查审批前无写入、批准后写入、差异、同ID不重调、第二轮恢复并读取、跨账号拒绝。单次完整测试4个模拟模型HTTP请求。
- `coding-agent/test/runtime.test.mjs`：真实SDK write/read/edit循环与历史恢复、审批拒绝/取消/超时闭环、链接路径防护、shell环境剥离和进程树取消。
- `CodingServiceTest`：owner隔离、路径/权限、幂等/版本/并发、审批去重/失效、取消等待、重启中断。
- 浏览器夹具：构建后的Vue，接口仅用协议夹具，桌面1440×1000/手机390×844；验证双模式、真实事件字段展示、审批、取消、刷新、diff、不可用状态、无页面异常及溢出。截图已人工查看。浏览器夹具不是线上或真实模型验收。

首次浏览器使用修复前构建，因 `text_delta.delta` 与界面读取 `text` 不一致导致回复未显示；修正事件字段后重新构建通过。初次SDK集成也修正了凭据存储公开导出接口；这些失败不隐藏。最终审查还修复取消后旧worker仍可能写入的竞争窗口、Windows路径别名和审批超时状态闭环。

## 能力边界与后续接续

- 当前执行于服务端受控私有项目，不能选择或直接修改浏览器所在电脑的任意仓库；已有文件可在项目面板新建/粘贴编辑。尚无桌面本地连接器、仓库挂载、批量目录导入、Git提交/分支/PR工作流或浏览器应用预览。
- shell以服务账号权限执行，**不是OS沙箱**，能访问该账号允许的项目外资源和网络。只面向受信管理员，普通账号无此工具；真正不可信多租户执行需容器或独立runner。文件边界不能阻止同OS身份的外部进程恶意并发改目录。
- Java文本编辑/差异单文件≤256KiB，pi文件工具≤64KiB；文本差异最多1000文件/8MiB，二进制及超限文件不包含。diff基线是最近一轮开始前，并非Git差异，也不覆盖每段历史的固定快照。
- 每账号30项目/100会话，每会话100轮，单实例JSON；上下文自动压缩关闭，长会话可能达到模型窗口。工具调用最多80次/轮，命令最多120秒，Node轮次10分钟硬超时。
- 原超级智能体仍按单次任务执行；新网页对话离开后不持久，不能称为跨会话长期记忆。Coding Agent历史与原伙伴记忆分开。
- 技能/插件按钮导航到已有模块；新Coding Agent未自动接MCP/技能市场，也未实现产品内多智能体并行调度。本轮“多智能体”指开发协作：前端、Java、pi运行时三个子agent与主agent独立集成验收。
- 后续由用户选择正式部署或继续本地连接器/仓库导入等增量；保留所有M014及以前未提交工作，不重跑既有M011/M013/M014真实模型探针。

## 本轮结果

本轮本地27项Java（含真实HTTP→Java→Node→pi SDK）、8项Node、16项浏览器检查及前端构建/Java打包通过；真实供应商请求0。Java为CodingServiceTest 8项、CodingAgentEndToEndTest 1项以及原DeploymentSmokeTest/PartnerChatServiceTest/PartnerWorkspaceTest回归；按最终Surefire XML汇总，不累加重复运行。Node 8/8、浏览器16/16。Java JAR打包成功，前端148模块生产构建成功。CI配置已更新，本轮未提交所以未远程运行。

最后一轮HTTP→SDK测试还验证了专用模型字段显式留空时复用原app.model配置。最终证据路径 `intern/tmp/coding-agent-m015-evidence/final-verification.json`。临时测试进程均在测试退出时关闭；没有部署或改变生产服务。

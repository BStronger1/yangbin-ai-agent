# 任务工作台第一版验收

日期：2026-10-16。代码目录为 `code/yu-ai-agent`，不与顶层 Recall Agent 混用。

## 实现

入口 `/tasks`、`/tasks/:id`。第一版为固定两人出行工作流：要求校验 → 个人知识库检索 → 天气与地图候选 → 模型生成/约束校验 → 方案保存 → PDF。

- `TaskService`：单进程 JSON 原子检查点、两线程/32 等待队列、每账号最多 5 个活动任务、账号隔离、requestId 幂等。
- `TaskWorkflow`：复用现有 RAG、规划依据、Spring AI 与 PDF 能力；模型结果须满足预算、时间、候选 placeId 及输出长度约束。
- `PlannerService`：任务 ID 对应稳定方案 ID 和 PDF 文件；任务检查点未确认时可找回已发布产物，PDF 先生成临时文件再原子发布。
- 前端：任务列表、六步时间线、事件、来源快照、产物下载，活动任务 2 秒轮询；刷新读取服务器状态，隐藏页暂停轮询；不确定提交保留 requestId，账号切换隔离草稿。
- `FAILED/INTERRUPTED` 可继续未完成步骤；取消等待当前调用结束，不启动后续步骤。服务停止时冻结计时，封闭迟到结果写入；下次启动等待手动继续。
- 天气/地点 unavailable 保存失败快照并暂停；disabled/out_of_range 按明确提示降级，地点 empty 不虚构地点。历史依据不会自动更新。

## 验证脚本

CI 精选 Java 测试新增 TaskWorkflowTest、TaskWorkflowIntegrationTest，EnabledFeaturesTest 新增 API 账号隔离。共 62 Java（原 45 + 新增 17）与 20 Python。

`deploy/turing/task-smoke.py prepare-restart` 创建一次真实任务，只终止同一用户、精确匹配本项目 app.jar 的 Java 进程，由既有 supervisor 拉起；就绪后 `resume` 验证中断恢复、六步结果与 PDF。此脚本会调用配置的真实模型，需操作者授权。

`deploy/turing/task-browser.py` 使用正常账号会话，验证完成任务、刷新、PDF、桌面/手机端，再通过界面创建并立即取消另一个任务。凭据只在服务器内读取，不输出。

## 本轮本地与 CI 结果

- 本地精选 Java 62/62、Python 20/20、前端构建通过。首次测试模拟重设触发空值异常，修正 Mockito 写法后完整复跑通过。
- 前端 Node 检查 6/6：提交不确定时复用 ID/原请求、刷新恢复及账号隔离、快速切换忽略旧响应、隐藏时中止、账号切换清空、卸载忽略晚响应。脚本保留在工作区 `tmp/task_frontend_contractcheck.mjs`，不计入 GitHub CI 测试数。
- 代码提交 `153fb5d` 的 [GitHub CI run 38851374690](https://github.com/BStronger1/yangbin-ai-agent/actions/runs/38851374690) 已成功：62 Java + 20 Python + 前端构建。

## Turing 实测

- 最终后端/前端为任务工作台实现，启动超时修复 `0ddf68a` 的 [CI run 38852023140](https://github.com/BStronger1/yangbin-ai-agent/actions/runs/38852023140) 已通过；GitHub contributors 核对为 BStronger1。
- 第二次更新健康检查成功，回滚备份 `releases/before-workspace-19be2685`；账号、知识库、历史方案、RAG/MCP 配置保留。
- 正常管理员会话创建北京 2026-10-17 两人出行任务 `193bed27-9cfc-40a0-901c-296cc5182947`；同 requestId 再提交返回同任务。RUNNING 时重启后端，重启后为 INTERRUPTED，调用继续接口完成同一个任务。
- 六步全部 SUCCEEDED，19 条事件，真实天气、12 条高德地点候选、真实模型方案与 PDF；记录 1 次模型调用、3 次逻辑工具调用、0 次失败尝试，累计步骤执行 38967 ms。该数字只对应这次定向验收，非吞吐/平均延迟 benchmark。
- 真实 PDF 2 页，已逐页渲染检查中文、来源和排版。主行程包含候选 placeId；文本仍需出行前核实，placeId 约束不等于所有自然语言事实已验证。
- 服务端证据：`logs/task-v1-state.json`、`logs/task-v1-verification.json`、`logs/task-v1-plan.pdf`；本地副本及渲染图在工作区 `tmp/task-v1-evidence/`。示例任务保留供演示。

- 浏览器验收通过：完成任务六步、PDF 下载、刷新恢复、1360px 桌面和 390px 手机布局（无横向溢出）、真实界面提交/取消后刷新仍 CANCELLED、DOM 无高德密钥、无 JS 错误。取消示例 ID `021824ea-5771-4eac-b0ed-813d5a2516b8`，未生成 PDF。
- 浏览器证据：`logs/task-v1-browser.json`、`logs/screenshots/task-v1-desktop.png`、`logs/screenshots/task-v1-mobile.png`；本地副本同在 `tmp/task-v1-evidence/`，已目视检查。

## 部署排障

首次更新因 Turing 共享 NFS 冷读缓慢，embedding 服务超过原有 120 秒就绪期限；更新脚本已自动回滚。将依赖冷启动上限改为 600 秒、更新健康检查总期限改为 900 秒，均保持有界等待和失败回滚。未禁用 RAG。任务中断验收只重启本项目 Java 后端，保留代理、MCP 与 embedding 服务，减少无关冷启动。

## 边界

- 固定工作流，不是任意自然语言任务规划器；模型不能动态增删步骤。本轮多智能体是开发分工，不是产品已具有多智能体执行引擎。
- 单实例存储，不支持多服务实例共享同一任务目录；无定时调度、插件市场、技能管理、跨会话长期记忆。
- 模型调用次数含失败尝试，外部请求无 exactly-once 承诺：模型已收到请求但检查点未保存时，恢复可能再次调用并计费。Token 返回 null 时界面显示未提供，不估造成本。
- 工具调用次数是知识库、实时依据、PDF 逻辑阶段尝试数，不代表底层 HTTP 次数；步骤耗时累计活动执行时间，不是排队与中断等待时间。
- 取消不是强杀外部请求；已发布的结果会保留。任务、方案、PDF 和账号目录需共同备份。
- 不将单次线上演示或模拟测试计数视为整体任务成功率，未做质量提升 benchmark。

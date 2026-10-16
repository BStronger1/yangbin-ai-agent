# 技能中心与执行计划第二版

日期：2026-10-16。代码为 `code/yu-ai-agent`，沿用 Spring AI / Vue，不与 Recall 或 Agent Workbench 混用。

## 功能与实现

- `/skills`：内置城市出行/雨天室内技能；个人技能复制、创建、编辑、启停、删除。输入为名称、简介、执行指引、知识库/PDF能力开关。每账号最多100个；内置只读；修改/删除带期望版本，冲突返回409。
- `SkillService`：按账号管理、JSON原子发布、启动读取；API不返回owner；`TaskService`冻结完整技能快照，后续变更不改已创建任务。
- `TaskPlanningService`：一次真实模型调用产生 `summary + steps(id,reason)`。必需requirements/evidence/model/save；knowledge和pdf按需求可选并受技能限制；只允许固定顺序、无重复、4–6项和有限文本长度。校验失败显式失败，重试重新规划，不伪装为模型成功。
- 第二版状态流：QUEUED → RUNNING(planning) → AWAITING_CONFIRMATION → 用户确认/start → QUEUED → RUNNING → SUCCEEDED。确认前不检索资料、不查实时依据、不生成最终行程/PDF；规划本身调用模型。
- 等待态独立持久化，重启保持等待；执行失败重试复用已确认计划，规划失败重试规划。取消/关停晚回包不复活任务，重复start幂等；同requestId重投在查当前技能前匹配冻结快照。
- 旧JSON和旧API请求保持六步直跑兼容。第二版展示多一步planning，因此完整计划为7个时间线步骤，最小计划为5个。

## 本轮验证

- 本地精选 Java 84/84（第一版62 + 第二版22），Python20/20，前端生产构建通过。
- 新增 SkillServiceTest7、TaskPlanningServiceTest5、TaskConfirmationTest9，以及EnabledFeaturesTest中技能HTTP CRUD/账号隔离1项。覆盖版本冲突、禁止越权、白名单、重复确认、等待态重启、执行中断恢复、取消、技能修改/删除后的快照、真正跳过可选步骤。
- 前端Node交互18/18：任务10项、技能8项，包含旧pending兼容、等待态停轮询、确认后恢复、路由/账号竞态、版本冲突保留草稿、重新读取失败不丢草稿。脚本在工作区 `tmp/task_frontend_contractcheck.mjs` 与 `tmp/skills_frontend_contractcheck.mjs`，不计入CI数字。
- 真实脚本：`deploy/turing/skills-smoke.py prepare`运行最小任务并准备完整任务，更新技能后重启本项目后端验证等待态。`skills-browser.py`完成技能CRUD/真实版本冲突和UI确认执行；最后`skills-smoke.py verify`收集结果。正常成功路径共4次真实模型请求，不使用伪造内部身份头。

- 代码 `344b7ed` 的 [GitHub CI run 38861950061](https://github.com/BStronger1/yangbin-ai-agent/actions/runs/38861950061) 已成功（84 Java、20 Python、前端构建）；contributors 核对仅 BStronger1。

## Turing 真实验收结果

- 部署成功，回滚文件包 `releases/before-workspace-13006fa3`。保留账号、私有配置、旧方案和任务；未再次发生部署回滚。
- 最小任务 `921cdd1d-d143-4f14-816a-cf2f0fb33ba6`：模型选择 requirements/evidence/model/save，确实不检索知识库、不生成 PDF；重复确认没有重复执行，最终 SUCCEEDED。模型调用2次、逻辑工具1次、失败0次。
- 完整任务 `6679fd39-2fdb-4d40-9827-adbaa99a998b`：模型选择完整6步；技能后来改成v2并停用，任务保持v1快照。同requestId重投仍返回原任务；后端重启后仍等待确认，确认前模型调用1次、工具0次、无最终方案。
- 在真实浏览器点击“确认并执行”后完整任务7个时间线步骤全部成功；北京预报可用，12条高德地点，知识库引用、方案保存和两页中文PDF通过。模型调用2次、逻辑工具3次、失败0次。PDF已渲染并逐页目视检查，中文与来源链接无裁切。
- 两组共4次逻辑模型调用。步骤累计耗时分别25306ms和22734ms（不含等待人工确认），仅是两项定向案例，不是准确率或性能基准；Token未提供仍为null。
- 浏览器通过：技能复制/创建/编辑、真实并发修改触发409且保留本地草稿、读取新版后保存、启停/删除、使用技能跳转预选、等待态刷新、UI确认、完成后刷新、PDF入口、无PDF任务、第一版旧任务兼容；1360px桌面和390px手机无横向溢出，页面无JS错误、无高德密钥。
- 首次浏览器验收因脚本使用精确label匹配含选项的select而失败，实际页面选值正确；改用combobox角色匹配，清理仅该次测试创建的技能后重跑通过。没有为此新增模型任务或费用。
- 内容质量负例：完整任务的模型输出包含无实时来源支持的“疫情期间/防疫”提醒，且价格、营业状态、交通耗时未核实。只确认工作流/引用/产物正常，不认定所有行程文本事实正确。后续应补陈述与来源对应检查及质量评测，不把这一案例当成事实核验通过。
- 服务器证据：`logs/skills-v2-state.json`、`logs/skills-v2-verification.json`、`logs/skills-v2-browser.json`、`logs/skills-v2-plan.pdf`、`logs/screenshots/skills-v2-*.png`。报告、截图、PDF及逐页渲染保存在工作区 `tmp/skills-v2-evidence/`（不进入公开仓库）。

## 边界与运维

- 这是出行领域的有界步骤选择，步骤依赖顺序固定；未实现任意任务分解、计划编辑/自动重规划、多智能体执行或外部技能代码安装。个人技能是版本化指引及能力约束。
- 模型能选择知识库/PDF，不能超出白名单。它是否正确理解偏好、自然语言地点描述及行程质量尚无benchmark；placeId约束不能证明所有文本事实正确。
- 技能删除不删除任务快照；已确认任务继续复用原依据。必须查询最新天气/地点时应创建新任务。没有语义级长期记忆增强。
- 单实例原子文件存储，无多机锁；Token未知返回null。模型调用次数含规划和生成尝试，工具次数为逻辑阶段而非HTTP请求。
- 备份 `tmp/skills`、`tmp/tasks`、`tmp/plans`、`tmp/pdf` 和账号目录。新版本读取旧任务；旧二进制不能读取第二版任务格式，回退到第一版前须保留新数据并制订迁移方案，不能直接让旧程序读取新任务目录。
- 本轮保留既有600秒依赖启动期限和900秒部署健康检查期限，失败会回滚部署文件。秘密仅在服务器私有配置中读取。

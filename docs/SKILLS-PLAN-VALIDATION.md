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

## 边界与运维

- 这是出行领域的有界步骤选择，步骤依赖顺序固定；未实现任意任务分解、计划编辑/自动重规划、多智能体执行或外部技能代码安装。个人技能是版本化指引及能力约束。
- 模型能选择知识库/PDF，不能超出白名单。它是否正确理解偏好、自然语言地点描述及行程质量尚无benchmark；placeId约束不能证明所有文本事实正确。
- 技能删除不删除任务快照；已确认任务继续复用原依据。必须查询最新天气/地点时应创建新任务。没有语义级长期记忆增强。
- 单实例原子文件存储，无多机锁；Token未知返回null。模型调用次数含规划和生成尝试，工具次数为逻辑阶段而非HTTP请求。
- 备份 `tmp/skills`、`tmp/tasks`、`tmp/plans`、`tmp/pdf` 和账号目录。新版本读取旧任务；旧二进制不能读取第二版任务格式，回退到第一版前须保留新数据并制订迁移方案，不能直接让旧程序读取新任务目录。
- 本轮保留既有600秒依赖启动期限和900秒部署健康检查期限，失败会回滚部署文件。秘密仅在服务器私有配置中读取。

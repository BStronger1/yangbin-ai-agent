# 当前项目记忆

记忆修订：M001 · 更新于2026-10-16（Asia/Singapore）。本次仅建立交接文档，以下运行结果来自同日第四版验收，没有因整理记忆而重新运行测试或部署。

## 项目身份与目标

- 项目：yangbin AI Agent；代码目录 `LLM-project/code/yu-ai-agent`。Windows工作区为 `F:/llm-workspace/intern`。
- 仓库：[BStronger1/yangbin-ai-agent](https://github.com/BStronger1/yangbin-ai-agent)，分支main。GitHub用户名以BStronger1为准。
- 用户希望参考提供的“插件、技能、工作伙伴”界面逐步打造可演示的Agent项目，支持Agent实习投递；当前主线是出行任务闭环和可验证的改进。
- 课程改造与个人扩展须准确区分，保留 [NOTICE](../../NOTICE.md)。不能把改名、单一contributor或AI辅助开发解释为全部从零原创。
- 这里的记忆用于开发对话接手。产品内聊天仍是最多20条窗口的持久会话，尚非跨会话长期记忆。

## 已完成的基线

| 模块 | 已有能力 | 主要边界 |
| --- | --- | --- |
| 基础工作台 | SSE聊天、ReAct工具、中文PDF、邀请制账号/管理员、用户数据隔离 | 管理员终端以服务账号运行，不是隔离沙箱 |
| RAG与MCP | 本地384维MiniLM、上传/索引/检索引用；独立MCP文档和时间工具 | 无混合检索/重排，Pexels尚未接通 |
| 持久会话与实时依据 | 按账号/会话/模式保存最多20条；Open-Meteo天气、高德地点、Photon回退 | 日预报、地图候选不证明营业/票价/路线 |
| v1任务 | 固定工作流、检查点、取消/重试、重启中断恢复、稳定方案/PDF | 单实例JSON，外部调用恢复可能再次计费 |
| v2技能与计划 | 内置/个人技能、版本冻结、有限步骤规划、人工确认后执行 | 出行领域有界规划，不是任意工具或产品多Agent运行时 |
| v3来源检查 | 地点名称引用、日期、预算、有限事实断言；网页/PDF报告 | 规则会漏报/误报，不是全文语义核验 |
| v4一次纠错 | 手动纠错、同规则前后检查、独立新版本/新PDF、持久调用记录 | 每planId一次逻辑尝试，只改文字并复用旧来源 |

## 版本与证据

- 文档建立前仓库HEAD：`f4160de`（第四版验收记录）；实际最近部署业务代码：`9cc73b0`。之后的记忆提交不会改变已部署业务代码。
- 第四版代码 [CI 38879477849](https://github.com/BStronger1/yangbin-ai-agent/actions/runs/38879477849) 成功：109项Java、20项Python、前端构建。前端另有15项本地交互契约检查。
- grounding-v2固定规则集26/26符合预期：23合成+3历史原文，16有warning/10无warning；非盲测，不是模型准确率。
- 真实任务/方案 `deb8e2df-b6d2-4dba-baf8-cfcb9f7cf23c`：v1保留；唯一一次纠错成功生成v2。同一grounding-v2检查warning 3→0、地点字面对应2/3→3/3；费用200/500元，结构/来源不变。旧v1的grounding-v1报告仍为2条，不能混用基数。
- 原Task、原Plan v1、原PDF哈希不变；重复POST复用结果，磁盘记录与API一致；桌面/手机、版本切换、刷新、新版两页中文PDF通过。
- **保留负结果：** v2曾生成无依据防疫提醒；v3有地点不对应和全天/全日天气断言。v4补全茶室名称，但“周边散步”现实适用性仍未证明，零warning不等于正确。
- v4纠错完成后未再线上重启；重启边界有本地测试，不冒充线上重启验收。
- 原报告：[第四版](../REPAIR-VALIDATION.md)、[第三版](../QUALITY-VALIDATION.md)、[第二版](../SKILLS-PLAN-VALIDATION.md)、[第一版](../TASK-WORKBENCH-VALIDATION.md)、[会话与依据](../MEMORY-LIVE-VALIDATION.md)、[高德](../AMAP-VALIDATION.md)。

## 不得破坏的约束

1. 纠错调用前持久化RUNNING/attempts=1。重复请求不得再次调用；中断保留消耗记录；PUBLISHING只恢复同一候选发布。禁止删除ledger来重新验收同一方案。
2. 保留原requirements/sources/evidence及站点数量、顺序、time/estimatedCost/placeId。warning严格减少、不新增warning类型、matchedStops不下降才发布新版本。
3. 原任务和原版本/PDF不被纠错覆盖；版本冲突必须拒绝覆盖。普通修订与纠错发布共享Planner锁；模型调用在锁外。
4. 旧报告/旧来源快照不静默回填；未核验价格/营业/路线持续标明。一次逻辑调用不保证SDK/上游精确一次计费。
5. 单实例文件存储不是分布式数据库；回退旧二进制前验证新格式兼容性，保留数据与凭据。

## 代码导航

相对仓库根：
- `src/main/java/com/yangbin/yangbinaiagent/tasks/`：TaskService、TaskPlanningService、TaskWorkflow。
- `.../skills/SkillService.java`：技能与版本；`.../planner/`：PlannerService、PlanningEvidence、PlanQualityService、PlanRepairService。
- `.../chatmemory/FileBasedChatMemory.java`：产品会话窗口。
- `yangbin-ai-agent-frontend/src/views/`：Tasks、Skills、Planner；`src/components/PlanQuality.vue`、`PlanRepair.vue`展示报告与纠错。
- `src/test/java/com/yangbin/yangbinaiagent/`：任务、规则、纠错与集成测试；`src/test/resources/quality/plan-quality-regression-v2.json`固定输入。
- `.github/workflows/build.yml`是当前构建检查入口；`deploy/turing/`为代理、账号、部署和验收脚本。

## 当前接续点

功能v4已完成，无待完成的代码修改或远程模型任务（截至M001整理）。下一步“独立任务评测与效果/时延/成本看板”仍为建议，尚未开始，范围见 [NEXT](NEXT.md)。开始新对话先核对Git、当前用户要求与本地运维记录，再选择最小可验收增量。

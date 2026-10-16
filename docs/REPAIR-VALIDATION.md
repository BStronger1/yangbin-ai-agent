# 一次纠错与复查第四版

日期：2026-10-16。建立在第三版来源检查之上。

## 行为与边界

- 任务页、规划页提供“按检查报告纠错一次”。由用户明确触发额外模型调用；不自动循环。按 planId（不是版本号）限制一次逻辑模型尝试。
- 使用原方案保存的要求、知识片段、天气与地点快照。不会重新查询天气或地图，历史依据仍可能过期。
- 只修订文字，保留站点数量、顺序、时间、估算费用和 placeId。候选须经过原结构校验；不能删站或删引用来隐藏问题。
- 本轮前后均按 grounding-v2 重检；新规则增加“全日”天气断言变体。旧版本中保存的 grounding-v1 报告保持原样，前后对照的“修订前”是重检报告。
- 接受条件同时满足：warning 条数严格减少、warning 类型没有新增、地点名称匹配数不下降。满足时保存 sourceVersion+1；拒绝或失败保留记录，不改原方案。
- 成功会新增方案版本；原任务状态、原任务中的方案版本及原 PDF 不变。新版本在规划页独立查看与导出，仍可选择原版本。
- 规则改进不是通用事实正确性证明。文字匹配、有限断言和预算算术不能验证营业、价格、路线或所有隐含事实。

## 持久化与并发

- `/ai/plans/{id}/repair`：GET 查询状态，POST `{expectedVersion}` 手动启动一次纠错。接口沿用账号归属、登录和 CSRF 校验。
- 调用模型前原子保存 RUNNING / attempts=1 到 `tmp/repairs/`。重复或并发请求返回同一操作；每账号最多一个 RUNNING/PUBLISHING。
- RUNNING 在服务关闭或重启后变为 INTERRUPTED，消耗记录保留；迟到结果不发布。网络结果不确定时，前端只查询状态，不自动重发。
- 候选先保存为 PUBLISHING 检查点再写方案；发布结果保存失败，可核对并复用相同新版本。恢复发布不会请求模型。其他修改先产生新版本时，纠错标记冲突，不覆盖它。
- 这是单进程、文件持久化实现，不支持多实例分布式互斥。“一次”计逻辑调用；模型 SDK 或上游服务重试不属于精确一次计费保证。
- 初始记录不能保存时不调用模型。记录损坏会阻止服务载入，以免悄悄丢失次数；需运维恢复备份。

## 验收入口

- Java：PlanRepairServiceTest；TaskWorkflowIntegrationTest 中真实 Planner/PDF 的纠错发布与冲突测试；其他既有回归。
- 固定规则集 `src/test/resources/quality/plan-quality-regression-v2.json`，输出 `target/quality-evaluation.json`。26例由23个合成场景和3段历史原文组成，是规则符合性检查，非盲测或模型准确率。
- `repair-smoke.py prepare` 只保存旧任务、方案、PDF摘要；浏览器手动触发一次；`verify` 检查前后版本与旧产物不变，并故意重复 POST 验证复用结果。
- 真实验收沿用第三版任务 `deb8e2df-b6d2-4dba-baf8-cfcb9f7cf23c`，不重建任务或替换原始失败表述。实际结果完成后记录于下文。

## 本地验证结果

- Java 首轮108/108通过；审查补修首次预留版本竞态后，定向25/25通过，包含新增的第16项纠错测试。最终配置共109个不同Java测试；Python20/20通过。
- 前端生产构建通过（127模块）；一次纠错10项、版本归属5项交互契约检查通过。
- grounding-v2固定规则26/26符合预期，16个有warning、10个无warning；输入SHA256 `bf3af74900885871a08e8db8c648e993f1f39eede484ff1a59ccae4cbbec17d2`。

代码 `9cc73b0` 的 [GitHub CI 38879477849](https://github.com/BStronger1/yangbin-ai-agent/actions/runs/38879477849) 已成功：109 Java、20 Python、前端构建；contributors核对仍仅BStronger1，课程来源NOTICE保留。

## Turing 真实验收

- 业务代码 `9cc73b0` 已部署，更新成功；回滚文件包 `releases/before-workspace-c9640c3b`。保留原数据。首次只读基线获取早于服务就绪，连接被拒绝，没有发送模型请求；就绪后读取成功。
- 沿用第三版任务/方案 `deb8e2df-b6d2-4dba-baf8-cfcb9f7cf23c`，浏览器实际点击一次，repair POST计数1，落盘attempts=1，最终REPAIRED并新增版本2。没有再次创建任务、模型重试或改写失败样本。
- 同一grounding-v2规则下原方案3条warning→候选0条，地点字面名称对应2/3→3/3。旧grounding-v1报告仍为原来的2条；新增的“全日有雨”检测使重检基线为3条，不能与旧2条混用作改进基数。
- 两处“全天/全日有雨”改成日期明确的“预报有雨”；第三站补全对应茶室名称。仍然描述茶室周边散步，规则只能证明名称对应，不能证明周边适合散步或营业、价格、路线已核验。零warning不是全文事实正确。
- 原要求、知识片段、天气地点快照、站点数量/顺序/时间/估算费用/placeId全部保持；新版本预算仍200/500元。没有联网更新快照。原任务对象、原方案v1及其PDF SHA256均未变化。
- API重复POST返回完全相同的已完成操作，没有再次模型调用；直接读取服务器 `tmp/repairs/<id>.json`，状态、attempts、原版本、候选及前后报告与API一致。此次纠错后没有再做线上进程重启；RUNNING与PUBLISHING重启边界由本轮本地测试覆盖。
- 浏览器通过真实按钮、成功后选v2、前后折叠对照、切回v1保留grounding-v1、链接打开v2、刷新、独立新版PDF及原任务下载链接不变。1360px桌面/390px手机无横向溢出，无JavaScript错误，DOM不包含服务器密钥。
- 新版本两页中文PDF已逐页渲染目视检查，含v2、grounding-v2、地点3/3、来源和明确限制；桌面/手机原始截图按区域检查可读性。
- 服务器证据：`logs/repair-v4-state.json`、`repair-v4-browser-started.json`、`repair-v4-browser.json`、`repair-v4-verification.json`、两个新版PDF及 `logs/screenshots/repair-v4-*.png`。
- 本地证据：工作区 `tmp/repair-v4-evidence/`，包括线上报告、固定规则报告、构建记录、截图、PDF和两页渲染。该目录不进入公开仓库。
- 这是一例定向功能验收，不能据此声称总体纠错成功率。下一步优先建立独立任务集，量化规则漏报、修订有效性、时延与成本，再扩大领域或工具范围。

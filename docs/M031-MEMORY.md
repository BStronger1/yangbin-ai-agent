# M031 记忆专项

2026-10-22。共同快照 `2f7a8d306638face8970a97152c709ff5f0c7193`；此专项只修改记忆服务、独立提取器、测试和本文。M029/M030 历史冻结、评分和真实报告保持不变。没有真实模型调用或部署。

## 新增行为

原有明确格式自动保存、ACTIVE 检索、版本修改、归档、忘记、账号和项目隔离保持。默认 autoExtract=false。成功任务的既有 `MemoryService.extract` 现在另外提取有限自然语言候选，例如“请记住：数据库是 PostgreSQL”“本项目的运行时为 Node 22”“我偏好先给结论”。未匹配表达不猜测。

自然语言只进入独立 `candidates`，不会进入 pi 上下文或自动成为事实。候选含来源会话/轮次、来源类别、创建与过期时间、版本、不确定性、冲突目标和目标版本；作用域继承由服务端 UserContext 确定的账号与已存在项目。只保留抽取后的必要文本，不复制完整历史。schema=1 增加兼容字段，旧文件可继续读取。

确认需要文档 revision、候选 version、冲突目标 targetVersion 三者匹配。已更正旧内容、被忘记名称及改名前名称不接受自动复活。候选 30 天过期，过期不允许确认；当前每项目累计候选上限 200，满额明确拒绝，不静默丢弃。拒绝/接受会清除候选正文；忘记条目会同时清除同名或关联候选名称和正文。旧对话、备份仍不随记忆忘记擦除。

内部 `proposeObservation` 区分 TOOL_OBSERVATION 与 MODEL_REFLECTION，标记为未经验证，禁止通过候选确认直接升级为用户事实。当前未接入工具/模型自动反思写入；调用者必须是可信后端，不能把模型声明的来源直接透传。凭据检查复用既有服务过滤；过滤不是完美 DLP。所有记忆仍不授予权限。

## integration 接口责任

仅为已认证用户新增以下控制器调用；账号取 UserContext，不接受 ownerId：

- GET `/ai/coding/projects/{projectId}/memory/candidates` → `service.candidates(projectId)`。
- POST `/ai/coding/projects/{projectId}/memory/candidates/{id}/decision`，请求 `{revision,version,targetVersion,accept}` → `service.decideCandidate(projectId,id,input)`。
- 不暴露 proposeObservation HTTP 路由；不为模型添加写记忆工具。

前端应显示来源、不确定性、过期与冲突状态，确认/拒绝后重新读取文档 revision，冲突时刷新而非盲重试。`PublicItem` 新增 sourceType/uncertainty。原 `extract` 返回值仍仅计明确格式自动保存数，候选数量用新 GET 读取，不应显示成已保存事实。

## 离线评测与限制

测试包含新服务实例恢复、来源隔离、敏感输入、去重、过时、版本冲突、遗忘清理和取消。固定合成候选语料有 12 条正例与 12 条负例；两条口语正例刻意位于规则之外，保留漏检。该范围只能说明确定性规则，不是泛化模型提取质量。

Node 检索语料 12 个查询，相关条目 12/12，选中 16 条，其中噪声 4，精确率 0.75，平均注入 311.67 UTF-8 bytes。前缀 `service_1` 同时匹配 `service_10/11` 暴露词法噪声；没有把合成集结果当总体收益，也未据此引入向量库或重排。

`evaluation/memory-m031-offline` 独立复现预算计算及摘要失败的判定：N01 第 7 请求累计预留 176892，第 8 请求需 30764，剩余只有 15108；拒绝是正确门控。N03/B 的 length 结果不接受、不自动重试、保留原始历史；thinkingChars 只作计数，绝不记录或还原隐藏思维内容。这里验证诊断与规划规则，没有宣称产品摘要生成质量已修复。

下一次真实四组研究须新任务、相同模型/工具/总预算、分层预定任务和严格失败规则；详见专项 prospective-protocol.json。协议是未授权草案，无可发送网络 runner。旧 STARTED 与历史余额不构成新授权。

## 本轮实际验证

- Java 22.0.1 / Maven 3.9.9 离线：MemoryCandidatesM031Test 10/10，既有 MemoryServiceM029Test 10/10，共 20/20。首次新隔离测试使用不存在的角色 other 导致夹具错误，改为合法 user 后全通过；不是生产缺陷。未运行整个仓库演示测试。
- 固定候选语料：正例 12，提取 10，漏检 2；负例 12，错误候选 0；自然语言直接写入事实 0。重复/冲突/敏感文本另由生命周期测试覆盖。
- Node 22+ 独立诊断 5/5，无跳过、无模型/供应商网络。
- 测试输出在该 worktree `target/surefire-reports`；只使用临时合成账号目录。系统 Maven settings 格式错误和离线仓库 ID 不匹配均保留为环境尝试；最终使用既有项目 settings，不读取其正文。
- 尚待 integration：候选 API/UI、跨模块端到端验证、必要部署；工具/反思候选只提供内部服务，未接入自动生成。摘要产品策略与新真实四组贡献评测尚未完成，不把诊断夹具当真实质量收益。

## integration完成记录
候选API/UI及真实HTTP确认已接入；遗忘历史名称/内容残留修复后专项Java21/21，集成Java87/87包含旧生命周期及新候选。字段兼容schema1。部署与模型质量未完成；详见M031-INTEGRATION。

# 任务与评测记录契约 v1

更新：2026-10-16。对应十步规划的第 1 步：先建立可追溯记录和离线导出，再建设独立任务集与看板。

## 本轮实现

入口：`tools/evaluation/export_records.py`，Python 3.10+ 标准库，无网络、模型或数据库依赖。读取稳定备份中的原始持久化 TaskService.State、PlannerService.Plan、PlanRepairService.State，以及任务的 PDF 文件；按明确指定的账号筛选，输出一个独立 run 目录：

- `report.json`：契约版本、runId、导出时间、样本来源、输入集合指纹、导出器源码指纹、带分母的任务状态汇总和限制。
- `records.jsonl`：每个任务一条记录，串联输入指纹、步骤/事件、方案全部已存版本、来源检查、纠错及原文件引用。
- `cases.csv`：任务状态、步骤尝试数、持久步骤累计时间、纠错差值；未知 Token 和费用为空。

本工具是拥有源文件读取权限的运维人员使用的离线工具，`--owner` 是导出范围，不是身份认证。不能将 CLI 直接包装成公开接口而省略服务端授权。默认不导出 prompt、知识库正文、行程文字、事件消息或错误原文，但账号、时间、ID 等仍是私有元数据，不是匿名公开数据集。

## 新任务执行记录

新增 ExecutionProvenance：TaskService 在每个逻辑步骤调用前，将 executionAttempts 与步骤检查点一起持久化；PlanRepairService 在唯一纠错预留中保存 executionAttempt。已成功步骤不会因继续任务重记，后续重试保留新旧尝试的各自上下文。新字段暂不加入网页 TaskView，不改变界面。

每条记录包含尝试编号、预留时间、结构化上下文 SHA256、配置模型名和可用的代码版本。app.build.revision / APP_BUILD_REVISION 由发布者提供准确提交（支持 -dirty）；缺失或格式无效为 null，不读取当前 Git 冒充历史运行版本。OpenAI 兼容模式读取 app.model.name；DashScope 读取对应 chat.options.model，避免错误记录 OpenAI 默认值。模型名是配置别名，不保证上游实际模型快照版本；非模型步骤不填写模型字段。该组件不读取密钥、URL 或完整环境变量。

原始逻辑输入只保存在原有任务/方案中，新增字段只增加指纹和允许的元数据。hashFormat=jackson-sorted-json-v1 对对象键排序并保留数组顺序；该指纹对应结构化上下文，不包含完整 system prompt，也不声明与 Python inputHash 或跨语言 RFC 8785 等价。

旧记录没有新字段时继续读取，不回填、不改写已完成任务。开始新尝试时才记录当时上下文。回退旧二进制前仍需备份并验证读取新字段的兼容性。当前本地实现未部署到 Turing。

## 使用

从项目根目录执行，输入应为稳定的离线备份，不是从 API 返回的 TaskView 临时拼装记录：

```sh
python tools/evaluation/export_records.py --data-root /private/snapshot --owner admin --output /private/evaluation-runs --sample-source historical_runtime
python -m unittest discover -s tools/evaluation -p "test_*.py" -v
```

Windows 可使用绝对 Windows 路径并用引号包住空格。普通账号传 32 位小写十六进制账号 ID。样本来源仅允许 `historical_runtime` 或 `synthetic_fixture`；这是操作者的来源声明，不是工具自动证明材料来源。

输入结构：

```text
snapshot/
  tasks/<taskId>.json
  plans/<taskId>-1.json
  plans/<taskId>-2.json
  repairs/<taskId>.json
  pdf/<owner>/plan-<taskId>-v1.pdf
  pdf/<owner>/plan-<taskId>-v2.pdf
```

`tasks/` 必须存在；无方案的未完成任务允许 plans/repairs/pdf 不存在。只处理任务关联的方案与纠错，独立规划页生成且没有任务的方案不在本版范围。PDF 仅识别稳定任务命名；其他下载文件未收集。输出必须位于输入目录之外；每次新建 UUID 目录，不覆盖既有 run。

导出会校验任务内嵌方案、磁盘原版本、纠错原版本、已发布候选的一致性，以及跨文件账号一致性。缺少必要版本、账号混用、JSON 损坏、非法计数或读取期间文件变化会失败，不将损坏数据包装成成功。两次指纹检查只能发现观察到的变化，不是在线事务快照；应优先使用停写/一致备份。发布失败可能留下 `.<runId>.partial` 目录，该目录不是完成的报告。

## 字段与数字口径

| 字段 | 口径 |
| --- | --- |
| schemaVersion | `agent-evaluation-records-v1` |
| runId / exportedAt | 本次导出标识与 UTC 时间，不是历史模型执行时间 |
| report.inputHash | 契约、账号、来源类别及纳入输出的原文件路径/字节 SHA256 的规范化指纹；重复导出不受 runId/时间影响 |
| exporterSha256 | 本次导出器代码文件指纹，与任务运行代码版本分开 |
| runtime.attempts / repair.executionAttempt | 新版逐次预留记录；含 codeRevision、modelProvider、configuredModel、inputHash 等。旧版为空或 null |
| runtime.codeRevision / model | 任务可能跨版本重试，不合并为一个版本；始终 null，查看逐次记录。历史缺失不能用当前 Git、当前配置或旧报告猜填 |
| record.inputHash | prompt + requirements + 冻结 skill 的规范化 JSON SHA256 |
| draftContextHash | 上述输入加持久 sources/evidence 的指纹；并非完整模型请求哈希，不包含 system prompt |
| executionPlanHash | 冻结执行计划的指纹；null 也有稳定的 JSON 指纹 |
| sourceFiles | 账号范围内任务/方案/纠错/PDF 的相对路径与原始字节 SHA256，供回查私有备份 |
| taskPlanVersion | 原任务关联版本；不会自动改成纠错后的最新版 |
| plans[].quality | 原保存报告；旧 quality=null 保持未检查，不重新运行规则 |
| repair.before / after | 纠错账本中的前后报告，独立于原方案旧报告 |
| repair.warningReduction | 仅前后 ruleVersion 相同且均有报告时计算 before−after；表示规则告警变化，不是语义准确率或模型整体收益 |
| modelStepAttempts / toolStepAttempts | TaskService 调用前持久化的逻辑步骤尝试；可能包含失败与重试，不等于 HTTP 请求数 |
| repair.reservedAttempts | 持久预留次数；存储失败或中断时不保证模型实际调用 |
| persistedStepDurationMs | 已持久化的步骤累计时间；不加导出时正在运行的区间。恢复时可能包含离线停机时间，不标成纯有效执行耗时 |
| createdToUpdatedMs | 创建到最后一次状态更新时间，包括等待确认、排队、重启和重试，不能当模型延迟 |
| repair.durationMs | 历史未记录有效调用耗时，保持 null |
| actualUpstreamRequests / promptTokens / completionTokens / cost | 未记录，保持 null，不从逻辑计数、出行预算或费用估算推导 |
| eventHistoryTruncated | 第一条事件 sequence>1 表示已有历史裁剪；本版不恢复删除的事件 |
| semanticCorrectness / semanticAccuracy | 无独立人工标签，保持 null |

completion 的分母为本账号全部导出任务；terminalCompletion 的分母只包括 SUCCEEDED、FAILED、CANCELLED。INTERRUPTED、待确认及运行中状态仍单列。分母为 0 时比率为 null。导出数据的完成比例只描述该份样本，不能推广成产品总体成功率。报告不跨版本汇总地点匹配率或规则告警率。

JSON 规范化按 Python 排序键、UTF-8、紧凑分隔符实现，拒绝 NaN/Infinity；不是跨语言 RFC 8785 声明。原文件 SHA256 额外保留字节级追溯。

## 验证与边界

本轮契约测试覆盖账号隔离、原始数据不变、重复导出、旧报告兼容、缺失指标、不同规则版本、错误/取消分母、版本错配、源文件变化、PDF 指纹与部分发布。测试使用合成夹具，仅验证导出器，不是独立模型效果评测。测试已接入 CI 配置；本地结果见 [本轮验收](EVALUATION-RECORDS-VALIDATION.md)。

已实现逐次执行上下文落盘与导出，尚未部署。发布时需提供准确 APP_BUILD_REVISION；未提供保持未知。上游 Token/请求计量、独立评测标签与网页看板尚未实现，历史未知值不会被回填。

# 出行评测集与人工标签协议 v1

日期：2026-10-16。十步规划第 2 步。代码与数据均为本地新增，未提交、推送或部署。

## 交付范围

冻结数据位于 `evaluation/datasets/travel-eval-v1/`：

| 组别 | 数量 | 内容 | 当前状态 |
| --- | ---: | --- | --- |
| grounding_replay | 12 | 固定要求、证据快照和方案文字；预算、来源支持、未知项表达 | 未运行规则；待人工标注 |
| workflow_fault | 8 | 来源失败、PDF重试、模型中断、重复确认、版本冲突、纠错无改善、跨账号读取、确认前取消 | 场景规格已冻结，故障适配器待第3步实现 |
| live_task | 4 | 最小输出、雨天备选、低预算、少走路偏好 | 新模型任务未执行；日期及实际来源待运行绑定 |

固定案例全部是合成地点和天气，`fixtures.invalid` 链接仅作标识，不可联网请求。真实任务组仅有合成需求文本；城市真实不代表已经获得天气/地图资料或模型结果。

这是与既有26例开发集分目录保存、在执行前冻结的新集合。**由同一开发助手编写，不是外部独立人类数据或严格盲测。** 作者已经了解系统能力和历史错误。输入/方案文本无完全重复，也不能证明语义独立或消除设计偏差。

## 冻结与复现

文件包括：

- `cases.json`：24个案例及各自inputHash/caseHash，全部NOT_RUN。
- `rubric.json`：标签取值、判断依据与证据要求。
- `author-notes.json`：作者参考判断，仅为AGENT_DRAFT_NOT_GOLD，不可作为人工金标准。
- `manifest.json`：版本、冻结时间、各文件SHA256、数据集合指纹、业务源码和旧开发集指纹、非盲测声明。

数据集合指纹：

```text
a1c7eda476c3bfedd5cea07bc1851ae920d91883dd39a2995c2b8b00ceef4974
```

`datasetHash` 是清单内3份数据文件名→原始SHA256映射的规范化JSON指纹，不是manifest自身的签名。校验时使用上面的独立固定值；CI契约测试也固定该值。修改数据并重算manifest不能通过固定值校验。

原开发集按defaults + overrides递归合并（数组及null整体替换），对比完整输入及itinerary规范化指纹。本轮24个新案例中12个固定方案与原26例均无完全重复；近义改写、题型重叠没有被证明不存在。

观察执行结果后不得覆盖此版本或调整预期来让测试通过；新增版本并保留原始结果。人工标签存放在数据目录之外，绑定datasetHash/caseHash/subjectHash；不修改冻结案例。业务源码改变时记录baselineSourceDrift，不能把新规则输出标作旧规则结果。

```sh
python tools/evaluation/dataset_contract.py validate --expected-hash a1c7eda476c3bfedd5cea07bc1851ae920d91883dd39a2995c2b8b00ceef4974
python -m unittest discover -s tools/evaluation -p "test_*.py" -v
```

## 人工标签流程

1. 两位评审使用不同别名，各自生成并填写评审包。工具不向外发消息、不替人填表。
2. 评审包只含固定输入/输出、准则和空标签，不带案例标题或author-notes中的建议判断。作者参考文件仍在仓库，因此这并不构成技术上隔离的盲评。
3. 逐维度填写label、reason和evidencePointers。证据指针从reviewSubject开始，例如 `/itinerary/summary`、`/evidence/weather/date`、`/requirements/budget`。
4. 完成后填写reviewedAt（带时区），并由评审本人确认humanDeclaration及independentDeclaration。工具只能验证声明和格式，不能认证真实人员、独立性或理由正确性。
5. 两份完整且别名不同的评审才能比较。分歧保持CONFLICT、consensusLabel=null；后续人工裁决需另存裁决人、依据和原始双评审指纹，不自动采用作者建议或简单补票。

```sh
python tools/evaluation/dataset_contract.py prepare-review --reviewer reviewer-a --output /private/reviews/a.json
python tools/evaluation/dataset_contract.py prepare-review --reviewer reviewer-b --output /private/reviews/b.json
python tools/evaluation/dataset_contract.py check-review /private/reviews/a.json --require-complete
python tools/evaluation/dataset_contract.py compare-reviews /private/reviews/a.json /private/reviews/b.json --output /private/reviews/comparison.json
```

当前生成的两份模板均为空：每份12个案例、36个待填写维度。**人工标注完成数为0。** 单元测试中的完整“评审”是临时模拟数据，仅测试校验器，未导出为本数据集标签。

标签定义：

| 维度 | 标签 | 说明 |
| --- | --- | --- |
| source_support | SUPPORTED / UNSUPPORTED / CONTRADICTED / NOT_ASSESSABLE | 由给定快照支持、无依据、与快照冲突、无法判断；无依据不等于现实中一定为假 |
| constraint_compliance | PASS / FAIL / NOT_ASSESSABLE | 检查明确预算、日期、时间顺序、地点ID等约束 |
| uncertainty_disclosure | ADEQUATE / INADEQUATE / NOT_ASSESSABLE | 费用估算、缺失天气、未核实营业/路线是否明确表达 |
| workflow_safety | PASS / FAIL / NOT_ASSESSABLE | 必须基于实际执行轨迹，不能把故障说明或预期断言当结果 |

标签不是规则告警数量的别名。零告警不代表SUPPORTED。两位评审一致也不等于答案准确；NOT_ASSESSABLE需单列，不进入语义正确率的已判断分母。比较工具只给维度一致/冲突计数，semanticAccuracy保持null。

## 第3步执行约定

当前工具只校验冻结与评审契约，**没有执行器，也没有运行这24个案例**。

- 固定方案组：后续调用冻结基线规则做离线回放。案例文本与对应证据保持不变；不得用当前网络信息覆盖合成快照。记录原告警与人工标签分别统计误报、漏报。
- 故障组：适配器应按每例fault/actions/assertions构造受控依赖和观察点；初始化方案、符号账号映射及故障注入的最终输入须另存并计算指纹。普通成功草稿可引用冻结G01，需告警的纠错初始方案可引用G12；具体解析产物必须在run中明确保存。此版是场景规格，不能称为8项已通过的故障测试。
- 真实任务组：运行时以Asia/Shanghai当天+2天物化日期，保留模板hash和物化输入hash、实际模型配置与工具快照。每例声明最多2次逻辑模型尝试、0次纠错；执行器需实现预算限制。本轮没有发起真实请求，声明不等于已有运行时预算控制。
- 故障与真实任务的12例必须绑定实际输出、轨迹及指纹后才能生成效果评审包；当前prepare-review故意只生成固定方案的12例。
- 对接上一步导出记录时，同时保留runId、caseId、datasetHash、模板inputHash、物化输入hash、代码/模型/规则版本及原始失败。已有导出器的task inputHash与此处case inputHash定义不同，不可直接等同。

覆盖审计通过仅证明格式、指纹、分组和评审契约可用，不是任务成功率、来源正确率或纠错收益。第3步再实现执行入口、采集结果和只读看板。

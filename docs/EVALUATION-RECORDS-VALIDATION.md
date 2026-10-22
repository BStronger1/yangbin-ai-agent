# 任务与评测记录第一步验收

日期：2026-10-16（Asia/Singapore）。基线 HEAD：f66db28。本轮为本地实现与只读历史数据验证；未提交、推送或部署。最近部署记录仍是 9cc73b0，本轮没有重新验证整站健康。

## 实现

- ExecutionProvenance 按每次逻辑尝试记录发布者声明的代码版本、配置模型别名、预留时间和结构化上下文指纹。TaskService 与 PlanRepairService 在执行前落盘，不改变唯一纠错次数、已有方案或 PDF。
- 旧任务/纠错记录缺少新字段时继续读取，不回填已完成记录；重试保留原尝试，追加新版本上下文。未设置 APP_BUILD_REVISION 时版本为 null。
- tools/evaluation/export_records.py 只读导出账号范围内的任务、方案版本、来源检查和纠错记录，核对 PDF 指纹，输出 report.json、records.jsonl、cases.csv。
- 不能混比 grounding-v1 与 grounding-v2 的告警；历史 Token、费用和未记录版本保持未知。代码版本是配置声明，模型名是配置别名，不是独立证明的上游模型快照。
- 无前端功能变化。说明与全部口径见 [记录契约](EVALUATION-RECORDS.md)。

## 本轮实际验证

| 范围 | 结果 |
| --- | --- |
| Java 明确列出的本地模拟/集成测试 | 114 tests，0 failures / errors / skipped；Maven package 成功 |
| 新增 Java 测试 | 3 项 provenance 单元测试 + 2 项任务持久化测试；已有纠错测试补充调用前落盘断言 |
| Python 导出契约 | 34 tests，通过，无跳过；合成夹具，不是独立模型质量集 |
| 既有 Python 运维/账号回归 | 20 tests，通过 |
| 历史真实记录 | 1 个已完成任务、2 个方案版本、1 个纠错账本、2 份 PDF |
| 重复导出 | 最终导出器两次 records.jsonl 完全一致，输入集合指纹相同 |
| 原始材料 | 6 份文件本地与服务器 SHA256 一致；导出前后本地字节不变 |
| 新模型调用 | 0；没有重置或重新请求既有纠错 |
| CI | 已加入测试配置；未推送，因此无本轮 GitHub CI 结果 |
| 浏览器/线上重启/前端构建 | 本轮未执行；没有 UI 修改 |

验证覆盖错误/取消任务分母、缺少数据、旧报告、账号隔离、版本不一致、不同规则版本、文件变化、路径逃逸、部分发布、跨代码版本重试及旧任务不回填。模拟模型测试不调用付费 API。

本机 Maven 全局 settings.xml 存在 XML 注释错误，首次启动在测试执行前失败；改用项目已有独立 settings 同时作为 -gs 与 -s 后，定向 42 项、随后完整 114 项通过。没有修改全局配置。

## 历史样本结果

本次只读复制既有任务 deb8e2df-b6d2-4dba-baf8-cfcb9f7cf23c 的持久记录和 PDF；没有重新生成方案或重新计算来源检查。

- 原任务仍关联方案 v1。
- v1 原报告仍为 grounding-v1；纠错账本的 before/after 均为 grounding-v2，按该账本记录告警 3→0。
- 两个版本及两个 PDF 均被独立引用。
- 原记录无执行版本元数据、Token 与实际费用，导出保持 null。
- 样本来自一个既有成功任务，不是随机抽样或新效果评测，不能从 1/1 推出系统完成率。

最终两个导出 runId：6f566ec8-bc12-4cc9-990a-5e25a1bce0cf、fe86ff0c-5097-4841-8324-b367b6bd5626。

输入集合 SHA256：bb79d0971833fc369e5820478516c182ce11af3005d362a9c807bae26188c760。

本地私有证据目录：intern/tmp/evaluation-records-evidence/，包含 snapshot/、exports/、verification.json、java-results.json、java-full.log、python-tests.log、python-existing.log。原任务内容与 PDF 不提交公开仓库。

## 尚未完成

独立任务集、人工语义标签、网页看板、上游 Token/计费计量尚未实现。运行时新字段尚未上线；发布时应配置准确 APP_BUILD_REVISION，并以新任务检查元数据，不改写既有任务。回退旧二进制前需验证其读取新字段的兼容性。

默认只导出任务关联的方案和稳定 PDF 文件名；单独规划页产物不在本版范围。CLI 的 owner 参数不是认证层。离线一致性检查不提供在线事务快照保证。

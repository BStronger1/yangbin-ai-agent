# 现成人工标签与本项目的辅助填写

核验日期：2026-10-16；M005更新见下。本轮选择“为本项目制作并填写辅助标签”，同时确认可用于后续外部基准的人工标注数据。没有把外部标签挪用到不相干的出行案例，也没有把AI判断计为人工。

## 可复用来源

| 数据 | 官方来源与标签 | 适用范围 | 本轮状态 |
|---|---|---|---|
| RAGTruth | [作者仓库](https://github.com/ParticleMedia/RAGTruth)：接近18,000条RAG回答，案例级及词级人工幻觉标注；有source_id、回答及span标签、train/test划分，仓库MIT许可 | 后续来源支持检测器的外部评测；QA、摘要、数据到文本 | M005已固定上游提交并导入24例test小样本；仅适用性审计，全部弃权 |
| FaithBench | [作者仓库](https://github.com/vectara/FaithBench)：人工标注摘要幻觉案例，原文、摘要与标注配对 | 后续摘要忠实性评测；与中文出行任务有领域差异 | 已核验说明；未下载、未导入、未跑分 |

RAGTruth的implicit_true明确区分“事实可能正确但所给上下文未提到”。因此本项目也区分“不受快照支持”和“已被证据反驳”。两者都不能靠确定语气证明。

后续若导入，建立独立数据集ID，固定上游commit及原文件hash，保留许可、原case/source ID、原标签及转换规则；按原test split报告，来源与多个回答不能跨训练/测试泄漏。不将外部英文摘要/问答的得分套到中文出行、预算或任务恢复。

## 已填好的项目标签

- [可读标签表](../evaluation/annotations/travel-eval-v1/README.md)
- [36维预填CSV](../evaluation/annotations/travel-eval-v1/assisted-review.csv)
- [完整原文、证据指针与理由JSON](../evaluation/annotations/travel-eval-v1/assistant-review-v1.json)

12个固定方案，每个包含来源支持、结构约束、不确定性披露三项。标签由同一助手在规则回放前填写；已读作者参考，因此不是独立盲评。后续只修正G01理由措辞和G06证据指针，均在第一次运行前完成。G11从作者草案CONTRADICTED改为UNSUPPORTED，原因是错日预报无法证明目标日天气相反。冻结作者文件不改。

M004制作时人工为0。M005用户已明确确认12例/36维，新增绑定原标签hash的human-confirmation-v1.json与confirmed-review.csv；单人AI辅助复核、非独立盲评。原预填CSV人工列仍保留空白，确认值在新CSV中；若人类看到辅助建议后复核，应明确记录为AI辅助人工复核，不能宣称独立盲评。原dataset_contract.py的人工协议保持严格，不接收助手schema；双人独立评审应使用不含建议的空表。

故障场景以实际执行断言为工程结果；4个真实任务模板尚无新输出，不能先填效果标签。

M005执行结果与适用边界见[SOURCE-REVIEW](SOURCE-REVIEW.md)和[本轮验收](SOURCE-REVIEW-VALIDATION.md)。RAGTruth小样本的人工标签不能挪用为出行案例答案。

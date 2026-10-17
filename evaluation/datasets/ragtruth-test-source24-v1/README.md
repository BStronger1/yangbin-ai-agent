# RAGTruth 外部人工测试小样本

官方来源：[ParticleMedia/RAGTruth](https://github.com/ParticleMedia/RAGTruth)，固定提交c103204b9ce28d6bbad859304bf30de72b8ed8fe。许可保留在[LICENSE](LICENSE)。标签、回答和源资料原样保存，不翻译、不补标签。

抽样在核验器实现之前固定：原test split且quality=good；QA、Summary、Data2txt各取按固定SHA256顺序排名最前的8个不同source_id，每来源再按响应ID的SHA256排名取1回答。共24来源/24回答，不根据原幻觉标签或检测结果筛选。它是小规模诊断样本，不代表整个语料分布。

数据指纹c66c5fc3e1516c8e109d838b5d7141b11be3670867229a78f0eb2f5563a75aab。manifest.json保存原始文件SHA256与抽样种子；用tools/evaluation/import_ragtruth.py对同一上游原文件可复现，不联网改写冻结文件。

本轮适用性审计：当前travel-claims-v1仅处理中文出行快照，24例英文通用资料均NOT_ASSESSABLE，覆盖0/24。原人工标签7例含幻觉跨度、17例无跨度；未把弃权视为无幻觉或算成正确。

后续接入真正支持自由文本来源的核验器时，保留全部24例、原标签和当前弃权基线，再比较；不可只挑易例填补效果数字。

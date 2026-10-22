# M030：可审计的短期/长期记忆四组评测

2026-10-22。产品基线M029；这批仅新增评测工程，不修改或再次部署产品。M029 v1/v2/v3原协议、结果与失败标记全部保留，不使用新评分器追改旧成绩。

## 已实现的实验

4个全新任务×4组共16个实例，两个独立轮次：导入第一阶段合成历史→真实pi compact→简短确认→同一持久会话追加第二段新历史→真实pi compact→回答或文件操作。所有组使用相同任务、模型、历史、预算及受控工具。每阶段26对中性进度+初始事实，共54消息，约23.6KB；这超过32k窗口下的SDK keepRecent阈值，实际压缩仍逐次检查。阶段确认也计模型请求。合成历史、强制压缩和配置文件编辑不等于自然仓库长期开发成功率。

A原生pi，B仅M027，C仅项目长期记忆，D组合；任务间轮换组顺序。N01真实修改JSON配置，N02更正，N03忘记及有效约束并存，N04跨项目同名约束干扰。任务事实、文件路径及评分见 [TASKS](TASKS.md)；正式评分见 [SCORING](SCORING.md)。本批没有增加模型自然语言提取或语义检索，也不测Java CRUD（已在M029工程验证）。

内容、格式、安全、运行结果分别记录。单个明确JSON围栏后附一致解释可评内容、格式另记失败；冲突/多个候选拒判。允许显式null作为可解释拒答保留配对。N01成功必须是目标文件读取成功→经审批写入成功→父端读取精确JSON结果；不接受自报updated。没有执行模型生成代码，shell始终关闭。既报告所有计划实例的ITT结果，也报告有证据配对及覆盖率，不删除失败样本。

## 冻结与预算

生产源码与新评测源码/协议/任务/评分文档逐项SHA256冻结在frozen.json；实际安装pi版本须为1.3.0，package-lock同样核验，不宣称对全部node_modules做字节审计。真实模型固定为deepseek-v4.1-flash-1017、既有兼容服务、默认强度；这是配置标识，不保证底层权重身份。

待授权整批硬上限：**128请求、每请求输出2048、3072000保守预留**；每实例8请求/192000保守预留/360秒，包括两次摘要、确认、工具追问及答案。没有付费judge、发现或重试。保守预留为请求UTF8字节+输出上限，不是实际Token或金额。费用UNKNOWN；真实预算须经用户明确批准后才能填授权文件。

最初草稿每阶段约5KB，实际SDK拒绝Nothing to compact，0请求；统一扩展后文件题又触发原128000实例预算。最终192000是在纯离线阶段按实际请求体校准；未自动扩展任何已批准付费预算。离线输出由确定性夹具生成，质量字段不作为真实模型分数。

真实执行前需要主智能体依据实际对话批准生成APPROVED授权文件，绑定frozen.json SHA、模型端点hash、唯一runDirectory、统一ledgerDirectory、batchId与额度。授权文件是工程互锁，不是人类授权证据。STARTED存在即拒绝，换run目录也拒绝；失败/中断不删除标记或补跑旧题。普通用户产品API无法选择这些开关。

每次准入后、发网络前同步落盘REQUEST_STARTED；usage到达记录REQUEST_FINISHED。断电后有开始而无结束的请求明确UNKNOWN。完整总量未知时inputTokens/outputTokens为null，knownInputTokens/knownOutputTokens只报告已知小计，不能重复相加或把0占位当免费。后处理异常有evaluation_error和partial-audit，不自动重试。隐藏thinking不保存，仅字符计数。

## 使用

先安装锁定coding-agent依赖，再在项目根运行无外部模型测试：

```powershell
node --test evaluation/memory-m030/*.test.mjs
```

真实执行仅在明确授权及隔离目录准备后由维护者运行；不得直接复制旧M029运行目录：

```text
node evaluation/memory-m030/run.mjs --real --run-dir <授权绑定的全新目录> --runtime-dir <冻结运行时目录> --authorization <主智能体生成的私有批准文件>
```

凭据仅在可信服务器内存中读取既有加密模型配置并传入，禁止写进任务/记忆/结果/公共授权模板。不要在命令行粘贴key。

## 标准benchmark边界

LongMemEval cleaned适合跨会话、更新、时序与无答案场景；LoCoMo须保留双说话人归属且有CC BY-NC许可边界。正式评测还需冻结数据revision/license/hash、隔离answer/has_answer/answer_session_ids/QA evidence、禁止oracle输入冒充检索；官方judge重试也必须计费。当前未下载/运行标准数据，本批不能称标准benchmark分数。官方链接和逐项审查见 [独立审评](REVIEW.md)。

## 当前结果状态

离线工程验收与真实结果分开。用户已批准128请求/3072000保守预留，父批次已结束：N01/A执行7请求后预算停止，其余15实例未运行；详见[真实结果](REAL-RESULTS.md)。N02–N04仅作为原额度内未发送任务另行冻结续接，不修改父批评分；产品线上仍M029。最终离线实测与证据见 [VALIDATION](VALIDATION.md)。

# M029 独立记忆评测

> 结果状态：下文是原始 v1 预注册说明。真实 v1 因 4096 窗口触发 SDK 输出钳制而无效，不能使用其原始零分估计记忆贡献；v2 因摘要失败仅完成 A 组。详见 [独立真实结果](REAL-RESULTS.md)。用户追加授权的全新 v3 为 8 请求 / 2048 输出 / 256000 保守预留，冻结与严格离线验收见 [V3-VALIDATION.md](V3-VALIDATION.md)，不覆盖或重放旧结果。V3 已完成 8 请求和 4 次真实摘要，但四组回答有 JSON 外解释，冻结质量分数均为 null；事后字段观察单列，不冒充预注册成功。

2026-10-22 冻结。先冻结协议、输入及评分，再实现适配器。32 个实例是 8 个自建 Coding 约束任务乘 4 组，不是 32 个独立任务，不是第三方 benchmark 分数。冻结前代码证据在维护者工作区 tmp/memory-m029-evidence/baseline.json，SHA-256 见 protocol.json。

## 归因与预算

A 为本项目 pi 原生压缩，关闭 M027 与新增长期记忆；B 仅开启 M027；C 仅开启新增长期记忆；D 同时开启。共同工具分页不计 M027 独立贡献。实验无既有手动偏好。每题四组必须使用相同模型、任务、预算、工具及权限，策略由可信运行环境选择。

固定已启用兼容服务型号 deepseek-v4.1-flash-1017，默认推理强度，不调用付费发现。兼容端点的型号仅为配置/供应商返回标识，未独立核实底层权重。真实运行前保存无密钥快照与当前源码 hash。每个 HTTP 请求（包括摘要）均计预算，无付费评审。每题按 A,D,B,C 顺序运行。最多 64 请求/512000 保守预留 Token，单实例最多 2 请求/32000 保守预留 Token，输出最多 512 Token，无重试。预留量是完整请求 UTF-8 字节加输出上限，不是供应商实际 Token。费用未知明确 UNKNOWN，不能把 SDK 0 价占位当免费。

C05/C06 为同会话压力任务，必须检查真实压缩事件。预算拒绝单列 budget_exhausted，quality=null，仍保留完整尝试分母；无压缩不推断短期收益。其他题为 Coding 约束微任务，不能代表真实仓库开发成功率。长任务需要扩展预算时另立协议，不改冻结结果。

## 评分与报告

只接受一个 JSON 对象（可有单一 JSON 围栏）。期望字段全部存在、类型和值完全相符，额外字段不加分，禁止值不得在原始回答出现。格式不符为质量失败；调用异常、超时、预算拒绝分开列出，不能伪装模型错误或剔除。C03 明确提示忘记，不能当召回收益；还需独立工程验证忘记后检索排除。

报告 8×4 完整矩阵、质量/安全分组、配对 B-A/C-A/D-B/D-C、调用数、真实 usage、未知 usage 数、保守预留、延迟、检索大小、实际压缩次数。只对两组均有有效质量标签的配对报告差，标明配对覆盖分母。8 题仅是试跑，不宣称总体提升或显著性。工程测试、回环模型集成、真实质量分文件。

真实运行先独占创建 STARTED 标记并保存协议/数据/模型 hash；失败或中断保留标记，禁止自动重放。每 task×group 独立临时 owner/project/session；只有同一实例内延续历史。gold expected/prohibited/类别标签绝不提供 runtime。shell 始终关闭，记忆不授予权限。

## 标准 benchmark 核对

- [LongMemEval](https://github.com/xiaowu0162/LongMemEval) cleaned 版适合跨会话、更新、时间和无答案场景；正式长历史成本超出本次微任务预算。has_answer、answer_session_ids、答案仅供评分，oracle 不当普通检索。代码 [MIT](https://github.com/xiaowu0162/LongMemEval/blob/main/LICENSE)；正式使用另冻结数据 revision、许可、hash。本轮未下载或运行。
- [LongMemEval-V2](https://github.com/xiaowu0162/LongMemEval-V2) 是 web/enterprise 多模态轨迹的经验记忆评测，采用独立 reader 和记忆预算，与本项目纯文本事实记忆不是直接可比；V1/V2 分数不混用。仓库标注 Apache-2.0。本轮只核对适用性。
- [LoCoMo](https://github.com/snap-research/locomo) 的双说话人对话必须保持实体归属，不能全部当当前用户事实；不覆盖 Coding 工具与压缩。正式使用需冻结版本并保留[许可原文](https://github.com/snap-research/locomo/blob/main/LICENSE.txt)。本轮未下载或运行。

## 独立工程要求

使用实际服务/store/runtime，禁止 toy 检索实现证明产品成功。工程验证记住→新会话→更正→新版本→忘记，重启恢复、账号/项目隔离、版本冲突、去重、归档、密钥拒存、恶意记忆不扩权及删除防复活。上下文检查实际模型请求；纯检索列表不等于模型使用正确。开发多智能体协作不等于产品多 Agent。

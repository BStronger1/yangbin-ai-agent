# M030 全新补充任务与夹具

日期：2026-10-22。这是四项自建 Coding 补充任务，不是 LongMemEval 官方样本或得分。本模块只创建输入、隔离夹具与父端文件判定；未调用模型、未部署，未修改 M029 冻结文件或生产 runtime。

| 任务 | 目标 | 长期来源 | 第一阶段短期要求 | 第二阶段短期要求 |
|---|---|---|---|---|
| N01 | 实际修改灾备恢复 JSON 文件 | 先前会话保存 storageClass | shardWidth | verifyMode |
| N02 | 采用熔断恢复更正后的版本 | remember → correct，旧值失效 | attemptLabel | minSamples |
| N03 | 忘记实验代号，保留独立有效事实 | remember → forget；有效 retentionHours | reportShape | includeRaw |
| N04 | 同 key 东西侧项目隔离 | 当前与外部项目各有 routeName | probeWindowSeconds | healthStyle |

名称、值与任务全部新建，不复用 M029 C01–C08、SM01、SM02。项目约束是合成输入，不代表真实供应商或部署配置。每阶段一个用户约束和确认，随后二十六对中性记录；中性句每条重复四次。各任务/组的中性历史完全相同，每段序列化 UTF-8 约 22000–26000 bytes，夹具硬上限 32000 bytes。第二阶段不重述第一阶段事实，不伪造摘要，也不预设模型会丢失约束。

## 主 runner 接口

createCase(absoluteRoot, task, group) 只接受 A/B/C/D，复用冻结 M029 prepareRuntimeFixture，建立每个任务/组独立 owner/project/workspace/session。它不调用 SDK、模型或工具，不播种会话。

返回原 workspace/sessionDir/memoryFile/ownerId/projectId，加 historyPhases、stage1Prompt 和可选 artifactPath。historyPhases 为两个 {id,messages} 对象；messages 为旧 seedHistory 接受的 {role,content}。task.sameSessionHistory 是两个阶段的平铺，仅兼容核对，禁止再重复播种。

主流程：seed 第一阶段 → 第一次 runAgent（真实 pi compact 后简短确认）；将第二阶段消息 append 到同一持久会话 → 第二次 runAgent（真实 pi compact 后最终任务）。必须观察两个成功压缩结果，才能标记多次压缩归因条件满足。阶段确认、摘要、工具续答均计入主协议请求、Token、延迟和预算，模块不重试。

导入历史与强制压缩是合成压力流程，不等于自然完成前序开发工作。真实模型可能完整保留信息，零收益或负收益都必须报告。

## N01 文件任务

workspace 初始仅有 README.md 与 config/recovery-manifest.json。业务字段初始为 null，固定 schema/readOnly 要保留。模型必须使用生产 read，再经受控审批使用 write/edit 修改指定文件，禁止 shell、执行生成代码或修改其他文件。

inspectArtifact(fixture, task) 仅由父端调用：逐级拒绝符号链接，检查普通文件和 64 KiB 上限，使用 JSON.parse，再以对象深比较验证 artifact.expected。返回 required/exists/schema/actual/bytes/score；无文件任务为显式不适用。非法 JSON、数组、额外字段、初始未修改文件、缺失文件都不通过。不会运行任何产物文本。

工具与审批记录由主 runner 提供。仅回答 updated=true 或单独文件内容匹配，都不能证明实际使用生产工具；完整验收还须有指定路径 read 和 write/edit 成功事件，以及父端审批证据。记忆、历史与任务文本不授予工具权限。答复评分与产物评分必须分开，不把自报当作 Coding 成功。

## 防答案泄漏与作用域

expected/prohibited/components/artifact.expected 仅留在评测侧，不能复制进 workspace、模型提示或压缩历史。夹具只把 memory 操作转换成生产格式的合成记录，将 artifact.initial 写入工作目录。事实位于其正式记忆/用户历史来源属于正常输入。

N02 ACTIVE 正文仅有最新版本；N03 忘记记录清空正文与 key，仅留 hash 墓碑；N04 外部项目同 key 记录位于另一项目目录，runtime 绑定当前 memory.json。M029 原实现不改。这是合成 runtime 夹具，不替代 Java CRUD、账户认证、真实存储生命周期验收。

## 本模块离线验证

2026-10-22 实际执行 node --test evaluation/memory-m030/fixtures.test.mjs，6/6 通过：两阶段公平/不重述、16组隔离、纠错忘记与跨项目记录、workspace 无判题元数据、父端文件判定、非法路径/组别负例。模型调用 0，部署 0。

首次将展开的大 JSON 放入命令行触发 Windows 长度限制，未生成文件；改为短 Python 模板后成功。这是离线写文件失败，不涉及付费重试。以上测试不是模型质量或生产工具闭环结论。


冻结前离线集成修正：最初每阶段约 5 KiB，小于实际 pi 在 32768 上下文中的 keepRecent 保留区，SDK 返回 Nothing to compact，模型请求为 0。现统一扩充为每阶段 26 对中性记录（事实不变，所有组公平），用真实 UTF-8 字节约束 22000–26000；主 runner 继续实测是否两次压缩成功及预留预算。此调整发生于任何 M030 真实调用之前，不是付费重放。

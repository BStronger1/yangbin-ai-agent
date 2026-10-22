# M017 多厂商模型接入与发布验收

> 后续 M028 已将配置入口移到总设置 `/settings`，支持服务连接、发现模型、多选和原生推理强度。下文是 M017 历史记录；当前用法见 [MODEL-SETTINGS.md](MODEL-SETTINGS.md)。
日期：2026-10-18（Asia/Singapore）。本报告是维护者本地证据，不随公共源码发布。

## 当前结果

M017（2026-10-18）已上线：http://172.16.185.103:18825/work，版本 `m017-fix-20261018-e05fc5717141`。账号私有BYOK支持Claude原生Messages与OpenAI/DeepSeek/Kimi/自定义兼容端点，对话和工作分别选模型；密钥AES-256-GCM加密、不回显，任务保存非密钥模型快照。公共源码已合并main `faf5934`，PR #1及预览版v0.2.0-preview.1已发布。最终Linux CI全部通过：198 Java、16 Node、29网关、86评测契约、28浏览器；Windows无全局密钥首次启动通过，线上UI25/25。修复后真实自选模型对话+审批建文件通过；首次真实空回答负例保留，合计2次对话提交和1轮Coding，供应商请求数/Token/费用未计量。48个升级前历史文件哈希及账号数量不变。

- 入口：http://172.16.185.103:18825/work；输入栏模型菜单 → 模型设置 → 填厂商、Base URL、模型ID和API Key；对话/工作分别选择。
- 最终部署 `m017-fix-20261018-e05fc5717141`，JAR SHA256 `a7b0052ad327bb60efb911c7251f89f0788e6c60c0c7aaf718e0e7a578aecb3a`。
- 原始M017发布 `m017-20261018-d5c9fd374361`，最终Java修复复用其逐文件相同的pi运行时，不重复安装未变化依赖。
- 首次升级备份 `releases/before-workspace-6bb62102`；最终修复备份 `releases/before-workspace-9c0623cf`。账号、tmp、knowledge均保留；模型主密钥和密文须共同备份。代码/配置可回滚，业务数据不会自动回退。
- [PR #1已合并](https://github.com/BStronger1/yangbin-ai-agent/pull/1)；[预览发布](https://github.com/BStronger1/yangbin-ai-agent/releases/tag/v0.2.0-preview.1)；[PR CI](https://github.com/BStronger1/yangbin-ai-agent/actions/runs/39058604929)；[主分支CI](https://github.com/BStronger1/yangbin-ai-agent/actions/runs/39059051893)。本地main与origin/main一致。

## 实现

后端`models/`、`ModelProfileController`、`PrivateChatModels`及Coding运行时传递选中配置；前端`ModelManager.vue`和`CodingWorkspace.vue`提供账号隔离CRUD、默认模型和分模式选择。空API Key编辑保留旧值，删除配置不会改变历史模型快照；执行中的配置被冻结，requestId绑定模型身份。

Java和Node均保留准确Base URL，原生Anthropic工具响应合并、DeepSeek/Kimi推理字段回放经过协议回归。普通账号仅HTTPS公网地址，连接时DNS复核，禁止重定向；管理员可用受信内网模型。密钥、错误回显和历史写入经过脱敏测试；不声称覆盖任意未知编码泄露。

增加无全局模型密钥的本机启动器、TLS origin设置、部署指南、贡献模板、来源许可边界和GitHub CI。修复DashScope自动配置空key启动、Windows Path/PATH冲突、Claude多Generation工具合并、工具终止丢正文、冻结输入Git换行变化。前端升级并移除旧head依赖，官方npm审计前端及runtime均0项；不代表Java或整个供应链无漏洞。

## 实际验证

| 层次 | 结果与范围 |
| --- | --- |
| 最终Linux CI | 198 Java / 16 Node / 29 Python网关 / 86评测契约 / 28浏览器；全部通过，Java无跳过；包含MCP、后端、前端构建 |
| 本地完整Java | 首轮197/197；正文修复另跑13/13定向，最终198来自Linux CI |
| 真实HTTP→pi | 无全局key启动、账号私有配置→真实Node/pi SDK→审批→写文件、换模型续接、历史和幂等；5次loopback请求，真实供应商0 |
| 独立Windows安装 | 全局key空、正常登录、CRUD/default、加密、退出及自有端口清理通过；真实供应商0 |
| 线上UI | 原始M017生产构建25/25；桌面/手机4图核查，JS/请求错误0；因并行临时profile操作，配置不变断言明确跳过。最终修复未改前端 |
| 最终真实模型 | 一次新对话含预期marker与DONE，无错误；一轮Coding在审批后写出byok-ready.txt，内容/差异/模型快照核验通过；临时配置已删除 |
| 历史保留 | 首次升级前48个文件哈希全部不变，账号数1→1；非模型质量指标 |

本轮真实调用使用服务器已有兼容模型网关。Claude原生、DeepSeek、Kimi协议通过本地模拟测试，不宣称分别用每家付费账号实测。未重跑M011/M013/M014/M015真实探针。

## 负例及修正

1. 前端最初14项依赖漏洞已升级修复；npm镜像404和一次TLS失败保留，不将扫描失败计为0。
2. 首轮浏览器字段顺序比较误报已改结构比较；最终28项通过。
3. 首次GitHub Java因冻结数据CRLF→LF失败；随后报告因关联开发集同问题失败。采用精确`-text`保留原字节和历史hash，不篡改数据或manifest。Windows默认git archive会恢复CRLF、掩盖问题，因此补`core.autocrlf=false`导出，6/6字节核对、86 Python及真实报告生成通过；最终Linux CI通过。
4. 首次线上对话模型正文为空并只调用doTerminate，断言失败，Coding未提交。代码审查另发现非空正文+终止工具会丢正文；已修输出与提示并补HTTP/SSE测试。修复后的新标记验收通过，首次失败未覆盖。

## 证据与禁止重放

本地总目录 `intern/tmp/model-profiles-m017-evidence/`；首次安装证据 `intern/tmp/m017-local-smoke/verification.json`。

- `ci-success-summary.json`、`ci-39058604929/`：最终PR CI完整日志；主分支CI链接如上。
- `live-browser/browser-report.json`与4PNG；`frontend-dependency-upgrade.json`及官方audit JSON。
- `ci-crlf-fix/linux-bytes/`：关闭Git自动换行后的真实输入和报告验证。
- `m017-byok-check-state.json`：首次真实对话失败，1次chat、0次Coding。
- `m017-byok-fix-check-state.json`：最终通过，1次chat、1次Coding；服务器同名logs标记禁止重放。
- `m017-data-preservation.json`、`m017-publish.log`、`m017-fix-publish.log`及发行manifest/sha。
- 体验项目“BYOK 模型接入体验”，session `801b7e50-bdfd-4b5f-b505-71d19753e365`，文件`byok-ready.txt`；可只读查看。

## 明确边界

当前网站仍是内网HTTP；公网域名/TLS尚未配置，UI提示不加密连接，公网部署须配置HTTPS。当前服务端私有工作区不是用户本机仓库；无Git/PR执行、应用预览、本机连接器、产品内多Agent并行调度或不可信多租户OS沙箱。用户本轮的多agent开发已完成，不等同产品自身多agent能力。

公开预览具备可复现启动、测试和贡献入口；用户满意度与GitHub高Star未验证也无法保证。整体上游许可仍不明，不将课程改造宣称全部原创或整体MIT授权。更广路线在ROADMAP/NEXT，不能把这些剩余项写成已完成。

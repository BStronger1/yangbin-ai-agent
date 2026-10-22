# M010 服务器发布记录

2026-10-17，用户明确要求将新版本部署到服务器。发布已成功，原入口现运行新版。

## 当前入口

- 技能广场：http://172.16.185.103:18825/skills
- 工作伙伴：http://172.16.185.103:18825/skills?tab=partners
- PPT工作台：http://172.16.185.103:18825/presentation
- /tasks继续承载出行任务，首页/与/skills为多领域广场。沿用原账号登录。

## 版本与备份

- 发布标识：m009-20261017-de1169ddb76e；HEAD仍为f66db28，发布的是包含M002–M009累计修改的工作区构建，不是新的Git提交；未推送。
- Agent包SHA-256：9018f43f855b4ebfde674fb1efc9ba1a68fe12111f3ab12b408730a061079562。
- PPT包SHA-256：82f957b9e34d1efb0ea8cf27f0e16a91c0ee3228e118e21842fddb1ff534f303。
- 服务器~/apps/yangbin-ai-agent/release.json记录实际文件hash；线上逐文件校验通过。
- 旧程序/前端/deploy/私有配置以及停机后一致的tmp、knowledge备份：~/apps/yangbin-ai-agent/releases/before-workspace-d6e0af7d。
- 对比备份，原账号记录完全一致，26个历史任务/方案/纠错/PDF/技能/记忆文件内容未变。新登录会话不纳入文件不变断言。
- update.py新增产物校验、停机数据备份、运行版本配置和就绪失败时程序/配置自动回退。本轮未触发回退。人工回退前核对新数据兼容性，不直接覆盖新数据或重置repair ledger。

## PPT运行方式

- 独立目录~/apps/yangbin-agent-ppt：app为本轮完整源码和dist-embedded，data为独立数据库/Redis/上传文件；数据库39442、Redis39389、API39811均只监听127.0.0.1。
- Agent代理经原会话与CSRF校验转发/ppt/，服务端身份桥映射独立用户；密钥仅保存在服务器私有配置，未写入仓库或本记录。
- 复用原ai-ppt-generator的只读Python/数据库运行时及服务器既有模型连接；原PPT实例的代码、账号、数据库、上传文件与运行服务不变。共享运行时后续升级/删除会影响本实例，需一起验收。
- 生产generation_mode=live，llm_configured=true，数据库与Redis健康。模型配置接通不等于本轮重新验证生成质量。
- 独立Supervisor托管postgres/redis/api/worker；安装独立用户@reboot条目，保留其他条目。Agent仍由原tmux会话托管；未改变其系统重启策略。
- 运维：`~/apps/yangbin-agent-ppt/runtime/bin/python ~/apps/yangbin-agent-ppt/narrative_service.py status`；需要恢复时用start。不要对已有数据重复运行setup或删除data目录。

## 本次验证

- 本地发布构建：19项Java定向测试通过，Agent前端、PPT独立版和嵌入版构建通过。不是完整Java测试集重跑。
- 线上11/11检查：18技能、8伙伴、PPT健康、正常会话项目列表、旧任务可读、新前端、匿名接口拒绝、发布文件校验、历史文件及原账号保留。
- 线上真实浏览器8/8检查：浅色侧栏、技能/伙伴目录、PPT免二次登录、创建表单、live模式、390宽无横向溢出、无页面异常。
- 本次部署验收没有触发付费模型、图片生成、出行重跑或纠错；不声称新的内容质量、Office兼容或并发能力结果。此前本地M009的17/17演示链路、12/12浏览器属于前轮证据，不与本次分母相加。
- 本机Maven全局配置格式错误通过显式指定现有项目settings解决；服务器共享存储首次初始化较慢，等待实际就绪后才切换，未重复初始化。

证据：intern/tmp/narrative-deploy-evidence/中的package-report.json、release.json、cutover.log、m009-deploy-verification.json、m009-browser-verification.json及线上截图；构建日志位于intern/tmp/narrative-embed-evidence/deploy-*.log。

最终已查看线上伙伴桌面/手机截图与PPT创建页；证据指纹收于final-deployment.json。部署及本轮验收完成，生产服务保持运行。

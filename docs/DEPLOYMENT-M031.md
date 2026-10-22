# M031 部署验收（2026-10-23，Asia/Singapore）

本轮重试SSH后恢复连接，2026-10-22启动部署、跨日完成验收。产品已部署 `m031-20261022-7c391172ee51`，发布源码为本地提交 `ee75736449f9c0a7541cdb2e1614f100c34f840c`（`m031/integration`，打包时干净）。没有推送Git，也没有付费模型调用。

入口：[Coding](https://www.yangbinai.homes/coding)、[总设置](https://www.yangbinai.homes/settings)。新页面需要正常登录。仅部署产品代码；质量评测、MCP/A2A真实第三方服务调用和终端开放均未执行。

## 实际发布与验证

- 部署前线上确认为M029：四本地健康200，128个发布文件哈希匹配，无活动任务；冻结100个原数据文件和8个账号的摘要，以及cron、共享服务进程、bootId和双终端关闭状态。
- 发布包152文件，JAR/前端/独立runtime源码均来自已构建集成树，包SHA-256 `ae04f6489dc6b3e35159ae55a7644ac8ac0ae38772b1a3124a7fdefc628fa73a`。不含密钥、用户数据、node_modules或构建缓存；根仓库专用的旧冻结拒绝测试不随产品runtime打包，它已独立通过。
- 在服务器Node22.19.0安装固定lock依赖后，pi1.3.0兼容检查通过；实际Linux运行时163项中162通过、1沙箱条件跳过。先安装/测试，再复核没有新活动任务，才停止本项目服务、复制一致数据备份并切换。
- 回滚备份 `before-workspace-9df9ee0d` 保存旧JAR、前端、部署脚本、私有配置和完整现有数据；100个原数据文件在备份中逐项hash通过。私有配置始终留在服务器，未输出或下载。
- 更新器返回`updated:true`，应用/内外网关/隧道四本地健康200，152个发布文件hash匹配。
- 发布后及验收后两次完整性检查均12/12：原100数据文件和8账号不变，cron、共享Caddy PID、隧道/监控pane PID、bootId保持，双终端仍false。仅新增3个合成验收账号，结束后全部停用，邀请已撤销；总账号11不等于原账号被改动。
- 正常登录线上API26项通过：记忆保存/跨会话查询/更正/归档/恢复/遗忘、版本冲突、账号/项目隔离、候选路由、无效执行模式拒绝且不创建轮次、子取消及产物权限负例、凭据拒绝、治理请求为0。
- 实际浏览器补验9/9：等待记忆面板加载、候选空态、通过真实API保存、ESC关闭、六种执行模式/team可选、模式弹层ESC、移动布局、模型请求为0。截图已目视复核。
- 公网正常浏览器四入口（/login、/coding、/settings、/）均200，正确渲染登录界面且包含本次构建引用；主JS/CSS各1个实际下载SHA-256匹配，共2/2。未登录重定向不当作已验证登录后的所有功能。

## 保留的失败与边界

首次线上浏览器在面板容器出现后立即检查表单，早于异步加载，断言失败；API26项已完成，测试账号已停用。没有重放整套API，而是新建浏览器专用验收账号、等待真实输入可见后补验9项通过。

服务器Python urllib裸请求公网返回Cloudflare403，整页HTML字节hash也不能等同构建标识。正常浏览器最终验证四入口、登录界面和两项静态资源hash通过；并未因此更改共享Caddy、隧道或Cloudflare规则。初始失败报告保留，不把它们改成通过。

未验证真实模型任务的质量收益、付费Teams、公网A2A服务端发现、持久OAuth管理或群聊；自然语言候选仍是规则提取，远程成员须可信注册配置，Teams产物仍隔离暂存。质量协议仍待真实runner/最终绑定及单独批准。终端隔离未验收，禁止宿主回退。

私有证据在 `intern/tmp/m031-evidence/`：package-report、release、deploy.log、deploy-completed、postdeploy-integrity、postacceptance-integrity、live-acceptance-status、browser-final-status、public-browser-check及online-memory-mobile.png。服务器一次性STARTED和COMPLETED标记均保留，不自动重放。仅更新本部署文档不触发再次产品部署。

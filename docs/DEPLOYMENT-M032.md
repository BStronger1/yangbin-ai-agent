# M032 部署与线上验收

2026-10-23（Asia/Singapore），本机目录接入已部署。

- 发布：`m032-20261023-f34e16c1f867`；源码 `f1483dfa1ac4b5c94b7459960c3b4f1b637668c8`，分支 `m031/integration`。Git只作本地提交，未推送。
- 发布包154文件，SHA-256 `96fdb7343249fc620ce0cff35db6b8ccae7b1aa48b7432018d54437071b54f44`。独立Node22.19.0/pi1.3.0运行器在目标Linux测试168项：167通过、1沙箱条件跳过。
- 回滚备份：`before-workspace-200ba9fa`，保留旧程序、配置及完整用户数据。依赖和测试完成后再次检查无活动任务，之后只停止/更新本应用及其public gateway。NFS复制期间等待原发布器，没有重复启动。
- 发布后、线上验收后完整性检查均12/12：154发布文件hash匹配、四健康200、原107数据文件/11账号不变、cron/共享Caddy/隧道监控pane/bootId不变、双终端false、备份内容匹配。
- 正常注册/登录的线上API10项与HTTPS浏览器9项共19/19：本机来源持久化、服务端文件接口拒绝、缺连接标识/团队/全允许在模型调用前拒绝、旧服务器项目仍可读写、浏览器选目录创建项目、直接文件写回/本地差异/刷新恢复、团队禁用及移动布局。新增4合成账号已停用、邀请已撤销，原账号保持。模型请求0。
- 公网正常浏览器 `/login`、`/coding`、`/settings`、`/` 4/4，以及主JS/CSS哈希2/2通过。原生文件夹选择器用OPFS合成句柄替身；实际浏览器File System Access读写经过验收，没有声称已自动操作用户真实代码目录。

用法：刷新 [Coding Agent](https://www.yangbinai.homes/coding)，打开“选择项目 → 添加本机文件夹”。桌面Chrome/Edge通过HTTPS授权。修改直接写回原目录，文件读取内容会发往服务端/所选模型；运行期间保持页面打开。终端、Git/测试命令、团队模式仍不支持本机项目。详细限制见[本机项目](LOCAL-PROJECTS.md)，离线验收与失败记录见[M032验证](M032-VALIDATION.md)。这不是本机常驻桌面执行器，也不是总体模型质量提升证据。

线上验收不调用真实模型，完整Agent链路由本地HTTP→Java→Node/pi→本地SSE模拟模型→独立磁盘目录回传测试覆盖。M029/M030冻结、旧授权与STARTED均未重放。

初次内网HTTP浏览器验收因非安全上下文按钮禁用而停止，10项API已通过且账号停用；随后HTTPS首次补验已完成原文件写入，但测试未展开details就断言可见文字而失败；第三次验收已展开差异，但Playwright isDisabled对option返回假而失败；本地已独立核对DOM disabled为真，改查DOM属性后第四个独立账号完成HTTPS 9项并停用，没有重放旧探针。此负例保留；没有为验收绕过浏览器安全上下文。

私有证据：`intern/tmp/m032-evidence/` 包清单、部署/验收状态、完整性报告及浏览器截图；服务器 `logs/m032-deploy-STARTED`、`m032-deploy-COMPLETED.json`、`m032-live/STARTED` 保留，禁止当重跑入口。原工作区源码/索引逐字节保持，后续从 integration worktree 继续。

# M034：VS Code 入口与文件审阅体验

日期：2026-10-26（Asia/Singapore）。状态：本地实现、构建与浏览器验收完成，未部署、未推送；不改变正在运行的 M033 评测基线。

## 代码与 worktree

- 产品基线：M032 后的 `284120c`。
- 编辑器及联合验收：`worktrees/yangbin-m034-editor`，分支 `m034/editor`；功能/修复提交 `0a14d77`、`8f3903e`、`1673391`。
- 独立体验改进：`worktrees/yangbin-m034-ux`，分支 `m034/ux`；提交 `66cc834`、`be3d904`。体验功能已集成到 editor 分支。
- 原工作区、m031/integration、M033 评测工作树和生产文件均未修改；原项目只更新私人交接文档。

## 用户使用

Coding Agent 选中本机项目后，右上角显示 **VS Code**。第一次填写本机项目文件夹绝对路径，例如 `C:\Projects\my-app`，点击“保存并打开 VS Code”，浏览器可能要求允许打开外部应用。后续直接点右上角入口即可打开整个文件夹；旁边下拉按钮可修改或清除路径。需要本机安装并注册 VS Code。

目录路径按账号、项目保存在当前浏览器 localStorage，不加入 API 请求、提示词或服务端项目元数据。浏览器目录句柄不暴露绝对路径，本功能不能自动核对输入路径与已授权目录是否同一位置；界面明确要求用户确认。文件夹改名、移动或换设备时需要更新路径。打开 VS Code 不会授予 Agent 新权限，不开启本机终端，也不自动保存编辑器中尚未保存的文件。

服务端项目仍显示入口，但说明没有本机目录绑定，可在“项目文件”浏览。不会用服务器路径、同名本机目录或上传副本伪装成同步项目。当前不提供服务端项目自动下载/同步或 Remote SSH 配置。

项目文件面板增加路径搜索、只看已改文件、已改标记、数量、选中态、复制内容、展开/收起全部差异。加载中、空结果与读取错误分开显示；刷新失败保留并标注上次列表。快速选择文件时，迟到的旧响应不会覆盖新预览；账号/项目/会话切换会清理筛选和旧请求状态。本机差异明确标注仅当前页面记录，搜索仅覆盖已加载文件，完整项目仍可在 VS Code 中查看。

在 VS Code 保存修改后，回网页点“刷新文件”或重新打开预览读取最新内容。保持运行中的本机任务页面打开；现有覆盖冲突与审批机制保持。

## 验证

以联合版本源码 `1673391` 为准：

| 检查 | 结果 | 范围 |
|---|---|---|
| Vite 生产构建 | 通过 | 集成前端 |
| Node 路径/URI与存储 | 2/2 | 中文/空格/#/%编码、非法URL/行号/相对路径拒绝、账号项目隔离、存储失败 |
| 新交互 Chrome | 25/25 | 首次配置、保存恢复、焦点、移动端、文件筛选、差异、复制、响应竞态、无路径API泄漏 |
| 新交互 Edge | 25/25 | 同上 |
| 本机文件既有浏览器回归 | 30/30 | 浏览器真实文件API/OPFS选择器替身；模拟API，无真实模型 |
| 跨工作区既有浏览器回归 | 31/31 | 历史/审批/账号/项目/移动端 |
| 弹层既有浏览器回归 | 44/44 | ESC、键盘、焦点、未保存保护 |

浏览器共155项通过，另外2项Node单元测试通过。全部浏览器API为测试夹具，不构成后端授权或真实模型评测证明；本轮无模型调用。截图 `editor-mobile.png`、`review-desktop.png` 已目视核对，移动端无页面横向溢出。

本机桌面验证：Windows 已注册 `vscode://` handler，按官方URL格式向系统派发了打开 editor worktree 的请求，派发未报错。但未观察到对应编辑器窗口，最近项目记录也未发现目标，因此**不宣称浏览器确认弹窗到原生VS Code窗口完整端到端通过**。产品提示只说“已请求打开”，不虚报“打开成功”；安装/外部应用许可由用户系统控制。

首次新测试发现保存后 opener 从 button 替换为 a，ESC焦点未恢复，已补回可见入口并完整复验。旧工作区回归寻找英文 `added`，随界面标签本地化更新为“新增”后31项通过；失败记录保留，不作为首轮通过。

## 证据与复验

私人证据：`intern/tmp/m034-evidence/` 下 editor-review-1（失败）、editor-review-2、editor-review-edge、local-files、workspaces（旧标签失败）、workspaces-final、overlays、native-protocol.json。构建日志 `intern/tmp/m034-build.log`。不将运行夹具记为 M033 正式结果。

在本 worktree 的 yangbin-ai-agent-frontend 目录，用 Node >=22.19 运行：

```text
npm run build
node --test tests/editor-link.test.mjs
node tests/editor-review.browser.mjs
node tests/local-files.browser.mjs
node tests/workspaces.browser.mjs
node tests/overlays.browser.mjs
```

浏览器脚本可用 `CHROME_EXECUTABLE_PATH` 指定 Chrome/Edge，`EVIDENCE_DIR` 指定新的证据目录。新测试拦截外部协议导航，以验证实际链接而不反复打开本机编辑器。

官方协议来源：[VS Code — Opening VS Code with URLs](https://code.visualstudio.com/docs/configure/command-line#_opening-vs-code-with-urls)。没有新增服务端执行器、系统协议注册或扩展安装。

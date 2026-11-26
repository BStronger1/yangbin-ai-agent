# yangbin AI for iPhone

iOS 客户端 0.4.0（build 4），支持 iOS 16 及以上。启动后进入 Coding。右上角「切换工作空间」可打开 Chat、Work、我的博客和我的知识库。各入口共用同一个 WebKit 登录会话。Coding 使用服务端项目，文件与测试命令在服务器授权的环境中执行。

## 在 iPhone 上写博客

1. 进入「我的博客」，点「写文章」编辑标题、标签和 Markdown 正文。写作与预览可切换，点「保存草稿」后可在其他设备登录同一账号续写。
2. 点「公开发布」，打开生成的公开文章链接。右上角「分享公开文章」调用 iOS 共享面板，读者通过 Safari 即可免登录阅读。继续编辑并保存草稿时，公开链接仍显示上次发布的版本。
3. 在已发布文章上点「导入知识库」，再从原生菜单进入「我的知识库」查询。同篇再次导入会更新对应文档。此功能需要部署博客版前后端并启用知识库嵌入服务。
4. 编辑页的「导出 Markdown」生成带 Hexo front matter 的文稿，可通过系统共享面板存入「文件」。设备间编辑发生冲突时可先导出本机副本。

## 在 iPhone 或模拟器上运行

1. 在装有 Xcode 16 或更新版本和 iOS 模拟器运行时的 Mac 上打开 `ios/YangbinAgent.xcodeproj`。
2. 选择 `YangbinAgent` scheme 和已安装的 iPhone 模拟器，按 ⌘R。应用默认连接项目的生产 HTTPS 站点。
3. 要测试自托管版本，在 **Product → Scheme → Edit Scheme → Run → Arguments → Environment Variables** 设置 `YANGBIN_DEV_ORIGIN`，例如 `https://agent-staging.example.org`。该地址需能从设备访问，使用设备信任的 HTTPS 证书，且在同一源上提供 Vue 页面及 `/api`。地址应只包含协议、主机和可选端口。开发构建支持此设置，Release 固定连接生产站点。
4. 真机安装时在 **Signing & Capabilities** 选择自己的 Apple 开发团队，并按需设置可注册的 Bundle Identifier，再选择连接的 iPhone 运行。分发包需使用对应团队签名。

在 macOS 上按 ⌘U 运行同源规则与工作空间路由的 XCTest。命令行也可从仓库根目录执行以下步骤，先列出设备，再替换模拟器 UDID。

```sh
xcrun simctl list devices available
xcodebuild -project ios/YangbinAgent.xcodeproj -scheme YangbinAgent \
  -destination 'platform=iOS Simulator,id=<SIMULATOR-UDID>' test
```

## 联调

- 初次打开 Coding 时应进入登录页，登录后回到 `/coding`。选择一个服务端项目，发送一条修复请求，在会话里查看工具结果、审批和验证信息。最新修复验证需要前后端都来自同一迭代。
- 从右上角菜单打开 Work；当前账号应保持登录。导出单个工作文稿，通过系统共享面板存到“文件”。Chat、Work 与 Coding 的模型设置和费用由接入的服务控制。
- 在飞行模式下重新载入，观察连接提示。恢复网络后点击“重新连接”，检查服务端已保存的会话。iOS 可能挂起后台页面，回到前台后以服务端保存的任务状态为准。
- 点击用户主动选择的外部 HTTPS 链接，会交给系统浏览器。工作空间只在指定站点内导航，不向页面注入原生文件或 Shell 接口。

## 0.4.0 与个人安装

本版连接配套生产工作台，提供Work证据转交、服务端候选审阅与下载、任务验证和恢复状态。iPhone不提供桌面本机目录执行器。CI会在macOS上运行iOS模拟器测试并生成Release真机架构的未签名IPA；未签名包必须完成个人签名后才能安装，CI通过不等于已经安装到真机。

没有Mac时，可使用[官方Sideloadly](https://sideloadly.io/)在Windows进行个人签名安装。自行在其界面登录Apple账号、连接并信任iPhone；不要将密码或验证码写入仓库。免费账号签名需按工具提示定期续签，详见[官方FAQ](https://sideloadly.io/faq.html)。签名安装和真机登录验收以实际设备结果为准。

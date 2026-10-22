# M032 本机目录：本地验收

2026-10-23，源码继续位于 m031/integration worktree。用法、架构和边界见 [LOCAL-PROJECTS](LOCAL-PROJECTS.md)。

- Node24.19.0 / pi1.3.0：169项，168通过、1条件沙箱跳过。新增本机通道5项全部通过。
- Java22：6类19项通过，含本机来源与单次请求通道3项、本机真实HTTP/Node/pi离线模型链路1项、原CodingService10项、原服务器真实链路1项、团队边界3项和团队真实HTTP链路1项。JAR打包成功。
- Chrome：本机文件流程30项、既有工作空间31项、弹层44项，共105项通过；前端构建通过，390px截图已目视检查。
- 浏览器测试是真实FileSystemAccess/OPFS句柄、读取、写入与IndexedDB恢复；系统原生文件夹选择器用测试替身替代。Java垂直测试使用独立磁盘合成目录模拟浏览器响应。没有宣称自动验证了用户真实目录/原生权限对话框。
- 付费模型调用0；新测试已接入CI，未推送所以未运行远端CI。旧M029/M030冻结与结果不改动、不重放。

负例保留：初次浏览器回归暴露普通HTTP缺少crypto.randomUUID导致界面初始化失败，已改为可选能力检测，原工作区回归31/31；首轮全量Node有2个既有测试竞态（70ms取消早于MCP请求到达、20ms团队deadline早于目录初始化），现分别等待实际请求进入和给初始化留出1000ms期限，生产超时不变；全量复测168通过1跳过。最初浏览器测试脚本落盘路径写重而未启动，修正后执行。截图首版在侧栏动画未结束时捕获，后续等待动画稳定后重验。

本机目录修改采用读取版本比对和浏览器暂存写入，不承诺跨外部编辑器的操作系统事务；断线/刷新后不重放文件动作。本机终端、团队、Git/测试执行、文件删除移动仍未提供，服务端原功能保留。当前页面差异缓存刷新后丢失，文件修改保留于原目录。

私有证据：intern/tmp/m032-evidence/browser-final2、workspaces-final、overlays-final；tmp/m032-{java-tests,node-final,workspaces-final,overlays-final,package,build}.log。现已部署，线上结果单独记录于 [DEPLOYMENT-M032](DEPLOYMENT-M032.md)。

线上首次使用内网HTTP，目录入口按浏览器安全上下文规则禁用，API10项仍通过。后续HTTPS首先因测试读取折叠details内容失败，再因Playwright option.isDisabled返回假而失败；本地独立DOM核对disabled为真，改为展开详情和检查原生disabled属性。各失败账号均停用，原始记录保留；产品二进制未因此反复部署。

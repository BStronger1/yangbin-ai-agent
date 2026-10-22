# 演示伙伴的 PPTX 交付

2026-10-17，M008。本地完成，未提交、推送或部署。沿用用户认可的 M007 技能广场和伙伴页。

## 使用

进入“工作伙伴 → 演示策划伙伴”，粘贴需求和材料，提交任务。生成后先查看逐页文稿，再点击“下载 PPTX”；也可复制或下载 Markdown。PPTX 为 16:9 原生可编辑文字，逐页讲稿写入演讲者备注。当前为简洁文字版式，没有图表、配图、模板导入或在线编辑器。

演示技能 presentation-outline 升为 v2，目录 work-catalog-v2；仍为 18 个入口（16 个文本生成技能含演示、2 个出行入口）、8 位伙伴。模型只生成内容，文件由 Java 本地排版。用户页数由模型遵循提示；导出严格对应保存的逐页结构，不额外插入封面，本轮没有真实模型页数遵循率结论。

## 数据与接口

- PresentationDraft 保存 title 与 slides，每页 title、points、notes；1–20 页，标题最多60字、页标题40字、要点最多4条且每条80字、每页备注2000字，模型原始输出总长40000字符。
- 解析严格拒绝未知字段、尾随对象和不合规结构；允许单层 JSON 围栏。不合规输出记录 FAILED，不自动再次调用模型或修复内容。
- WorkService.Run 新增可选 presentation；旧任务缺字段仍可读取，保留原 Markdown，没有结构的旧演示任务不会伪装为可导出文件。
- POST /api/ai/work/runs/{id}/presentation 创建或复用文件，GET 同路径下载附件。每次操作先验证账号与任务 UUID。
- 文件位于 app.work.dir（默认 tmp/work）下的 {owner}/artifacts/{runId}/presentation.pptx 和 artifact.json。索引保存结构/字体/渲染器输入 hash、文件 SHA-256、字节数、页数与时间。
- 重复导出校验缓存；损坏文件或 JSON 索引可从原任务重建。原任务不改、模型不再调用。下载有 attachment 和 no-store 响应头。
- 单实例同步导出、原子替换文件/索引；二者不是数据库事务，失败后显式重试可恢复。既有账号100任务上限保留，未新增取消、归档或任务删除。
- 前端绑定账号、路由、任务 ID 和当前导出请求；切换账号/任务取消请求并丢弃迟到结果。下载失败仅重试导出文件。
- 渲染器依据文本高度调整标题32→24pt、正文24→18pt；仍无法放入时明确拒绝，不裁切正文。当前未提供在线修改结构的入口，极端内容需调整输入后新建任务。

实现：work/PresentationDraft、PresentationRenderer、WorkPresentationService，controller/WorkPresentationController，WorkDesk.vue。Apache POI 5.5.1 使用官方 XSLF API：[官方示例](https://poi.apache.org/components/slideshow/xslf-cookbook.html)。

## 字体与发布

Windows 默认 Microsoft YaHei。Linux 必须安装能显示中文的字体并设置 PRESENTATION_FONT，例如 Noto Sans CJK SC。配置入口 app.work.presentation-font；运行环境没有中文字体时拒绝导出。字体不嵌入 PPTX，接收方字体替换可能改变布局。

CI 增加 fonts-noto-cjk 安装和 test.presentation.font 参数，本轮未远程运行。发布前仍需真实模型结构遵循、完整账号 HTTP 链路、Linux 字体和 PowerPoint/WPS 兼容烟测，并备份 work 目录。未修改线上服务，历史部署代码仍为9cc73b0，不能据此推断当前线上健康。

## 本轮有限验证

| 范围 | 实际结果 |
| --- | --- |
| Java | WorkPresentationTest 7 + WorkServiceTest 7，共14项通过 |
| 前端 | npm run build 通过 |
| 浏览器 | 真实 Vue 应用、模拟 HTTP、真实 Java 生成 PPTX 夹具，13项通过 |
| 文件 | 6页合成营销案例，逐页原生文本/要点/备注一致，原任务字节不变 |
| 视觉 | POI 原生渲染6页并查看总览；1440桌面/390手机下载区域无横向溢出，手机截图已查看 |

Java 覆盖导出、缓存与损坏恢复、服务/控制器账号隔离、结构约束、模型格式失败不重试、旧记录兼容、密集中文布局拒绝边界。浏览器覆盖下载字节与文件名、重复导出不重新生成内容、失败重试、错误任务绑定、账号切换丢弃迟到结果及旧文稿兼容。

保留负结果：初次编译误用 notes API；初次密集标题超高，26pt测量为84.07pt仍超过84pt文本框，增加24pt下限后通过。本机 PowerPoint COM 启动失败（0x80080005），已清理本轮无窗口进程；改用 POI 渲染，不称为 PowerPoint 验收。未跑真实模型、完整登录 HTTP、Linux、WPS 或全部历史回归；上游模型请求0，替身数据不是内容质量证据。

证据：intern/tmp/presentation-partner-evidence/，含 java-final.log、frontend-build.log、browser-report.json、页面截图、marketing-example.pptx、run.json、artifact.json、slides/、slides-contact.png、verification.json，以及失败日志。样例为合成案例，无真实营销收益。临时 Vite 与前端夹具验收后清理。

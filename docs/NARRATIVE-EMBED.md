# NarrativePPT 嵌入 Agent（M009）

2026-10-17，M009本地集成完成，后续M010已部署至原服务器入口；源码未提交/推送。用户要求将 LLM-project/ai-ppt-generator 的功能嵌入 yangbin AI Agent；采用完整 React 工作台嵌入和专用 FastAPI/ARQ 服务复用，不复制另一套缩水生成器。M008 Java 简版演示及历史记录保留。

## 用户入口与功能

技能广场 → 工作伙伴 → NarrativePPT 演示伙伴 → **召唤 / 进入伙伴** → **新建或继续 PPT**，进入 /presentation。Agent 侧栏保留，右侧是 /ppt/ 下的 NarrativePPT。Agent 登录态自动映射，无第二次注册/登录；NarrativePPT 原独立版仍可使用。

复用文档/文字/主题输入、六类听众与目标/时长、叙事方案、可编辑大纲、后台分页生成与实时进度、来源面板、逐页原生编辑、主题/版式/图片、AI修改入口、质量提示、演示和PPTX导出。AI生成和修改依赖 NarrativePPT 的全局或用户模型配置；没有把 Agent 的密钥发送到浏览器或自动复制配置。演示模式只用规则生成内容，不能证明真实模型质量。

手机编辑器把页面列表改为“页面”按钮展开、选择后收起，工具面板覆盖显示；保持桌面布局和全部原编辑入口。服务未配置/依赖不健康时给出可重连提示，返回简版演示仍可用。

## 架构与隔离

- Agent 的 aiohttp 入口代理新增 /ppt/* 与 /api/ppt/status。所有静态页面和数据请求先检查现有账号会话；写操作继续检查同源 Origin 与 CSRF。
- 服务端覆盖 X-Agent-Owner、X-Agent-Bridge-Token；剥离浏览器传入的身份、Cookie、Authorization 和内部凭据头，固定转发到127.0.0.1上的 NARRATIVE_PORT。不允许用户选择上游网址。
- 专用 NarrativePPT 设置 AGENT_EMBED_ENABLED=true，AGENT_BRIDGE_SECRET 至少32字符；所有 /api/ 请求都要求桥接凭据，关闭密码登录/注册。缺配置启动失败，默认独立模式不变。
- Agent admin/32位账号ID通过固定命名空间UUID映射为独立 NarrativePPT 用户；首次访问并发创建有唯一约束兜底，不按邮箱绑定已有用户，原有模型配置保留。不要把独立服务已有账户数据库直接换成嵌入实例。
- 项目/文档/编辑/导出继续使用原 OwnedProject 所有者约束。嵌入媒体另按 URL 中的用户UUID检查所有者，防止另一账号已知图片URL时读取。
- 前端子路径 /ppt/、API /ppt/api/v1、SSE、二进制下载和媒体URL统一适配。请求前检查 Agent 当前身份与绑定身份一致，切换账号后不能把旧编辑操作发给新账号；父组件切换账号卸载 iframe。
- 网关上传上限12MiB，NarrativePPT保留10MiB文档/8MiB图片实际限制；SSE即时转发，上游连接5秒、读空闲660秒，无自动重试。响应 no-store，不透传服务端 Cookie。
- 数据库、Redis、文件存储由专用 NarrativePPT 实例管理；原 Java /ai/work/runs 不承载高级演示项目。高级历史在“我的PPT”，不是与简版任务合并为同一记录。

## 启动配置

先按 NarrativePPT 的依赖说明准备 Python3.12、Node 与独立 PostgreSQL/Redis。保持原项目数据库和存储不变。在 NarrativePPT/frontend 执行：
```sh
npm ci
npm run build:embedded
```
生成 dist-embedded；npm run build 仍生成独立版 dist。

在同一受信任服务环境为 Agent 网关和 NarrativePPT 设置同一个随机 AGENT_BRIDGE_SECRET（不少于32字符，不要提交）。NarrativePPT 另配置 DATABASE_URL、REDIS_URL、MODEL_CONFIG_SECRET（若要保存用户模型密钥）；真实生成还需全局 LLM_* 或用户模型设置。网关配置 NARRATIVE_PORT=39801。

Agent 仓库下运行：
```sh
python tools/run_narrative.py --source ../../ai-ppt-generator --runtime tmp/narrative --port 39801 --demo --migrate
```
启动器默认用来源项目 backend/.venv，也可 --python 指定；--demo 为无付费模型模式，去掉后才使用真实模型。--migrate 明确升级所配置数据库，启动API和worker，Ctrl+C清理它们。运行目录隔离文件和日志；不读取来源项目的私有.env。网关仍按现有部署方式运行，读取相同secret与NARRATIVE_PORT；本地可用 AGENT_FRONTEND_DIR 指向 Agent 前端 dist。仅运行 Vite 或 Spring Boot 不能提供账号与 /ppt/ 代理。

本轮真实链路使用隔离测试 PostgreSQL/Redis 容器、API/worker/Agent代理，启动器另做 --help/编译检查；未验证进程守护、自动重启、正式部署或公网HTTPS。不要把本地端口当线上地址。

## 本轮验证与证据

| 项目 | 当前证据 |
| --- | --- |
| 账号桥接 | 8项Python通过：稳定/不同映射、错误断言、首次创建、配置保留、直连拒绝、禁注册登录、短secret拒绝 |
| 网关 | 12项真实HTTP通过：5项嵌入 + 7项原代理回归，含伪造身份、CSRF、3MiB上传、SSE、禁用状态和退出 |
| 原版登录 | 独立模式在隔离PostgreSQL上9项原注册/登录测试通过 |
| 完整链路 | 17项真实API通过：导入Markdown、6听众、6页叙事大纲/编辑/确认、真实ARQ生成、SSE、页面修改、图片上传读取及路径穿越拒绝、主题、PPTX及备注、跨账号项目/导出/媒体拒绝 |
| 浏览器 | 12项生产构建/真实会话检查通过：内嵌列表/编辑器、无二次登录或token、子路径图片实际显示、390宽画布/页面切换、完整创建选项、无JS异常 |
| 构建 | Agent、NarrativePPT嵌入版与独立版通过；嵌入包约924kB，有现存大包告警 |
| 内容 | 一份合成6页样例，导出回读含修改的可编辑文字和逐页备注；演示模式，外部模型调用0 |

证据 intern/tmp/narrative-embed-evidence/：e2e-report.json、embedded-demo.pptx、browser-report.json、页面截图、各测试与构建日志。已查看真实桌面列表、编辑器和修复后的手机截图。首次手机页面栏挤压/顶栏重叠已修复；初次测试缓存目录权限告警通过禁用pytest缓存规避，测试本身通过。没有新增模型质量结论，也没有PowerPoint/WPS验收。

## 完成审查

完整功能复用以同一 NarrativePPT App/API/worker 源码为依据；没有隐藏原有大纲、来源、模型设置或编辑入口。新增桥接、路由、媒体、上传/SSE/导出已分别有HTTP与浏览器证据。真实模型、图片供应商与高级AI修改未在本轮重新调用，这些是既有功能的模型配置/供应商验证边界，未声称新的效果。M009本地开发时未授权正式部署；后续用户明确授权，M010已发布，详见[发布记录](DEPLOYMENT-M010.md)。两个仓库原有未提交修改保留。

## M010 首次上线状态

原入口18825现已运行新版，/skills为技能广场、/skills?tab=partners为伙伴、/presentation为PPT；生产服务位于独立~/apps/yangbin-agent-ppt，API39811。已通过11项线上接口与8项真实浏览器检查；账号与26个历史文件保留。原服务器模型连接已配置，验收未调用模型。版本、备份与运维见[发布记录](DEPLOYMENT-M010.md)。

M011已将NarrativePPT显式设为伙伴并直达完整工作台，当前发布及能力边界见[伙伴验收](PARTNERS-M011.md)。旧简版技能与任务保留。

M012已统一伙伴主页、详情和账号授权；工作台访问受伙伴授权约束，见[本轮验收](PARTNER-WORKSPACE-M012.md)。

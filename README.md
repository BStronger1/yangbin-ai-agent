# yangbin AI Agent

由 [BStronger1](https://github.com/BStronger1) 维护的 AI 智能体学习项目，包含恋爱问答、多轮对话、ReAct 工具调用和中文 PDF 生成。基于教学项目改编，来源见 [NOTICE.md](NOTICE.md)。

## 技术栈与模块

- Java 21、Spring Boot 3.4.4、Spring AI 1.0、Vue 3、Vite。
- 根目录：后端，包名 `com.yangbin.yangbinaiagent`。
- `yangbin-ai-agent-frontend/`：网页界面。
- `yangbin-image-search-mcp-server/`：独立 MCP 服务，提供知识库读取、时间查询，以及可选 Pexels 图片搜索。
- `deploy/turing/`：用户目录部署、同源代理与服务管理。

## 本地运行

安装 JDK 21+、Maven 3.9+、Node.js 22。在终端设置环境变量（`.env.example` 是配置示例，Spring Boot 不会自动读取 `.env`）：

```bash
export MODEL_PROVIDER=openai
export MODEL_BASE_URL=https://api.openai.com
export MODEL_NAME=gpt-4.1-mini
export MODEL_API_KEY=你的密钥
mvn spring-boot:run
```

兼容 Chat Completions 的其他服务可填写自己的地址和模型。地址支持有或没有 `/v1` 后缀。使用百炼时设置 `MODEL_PROVIDER=dashscope` 和 `DASHSCOPE_API_KEY`。

```bash
cd yangbin-ai-agent-frontend
npm ci
npm run dev
```

打开 `http://localhost:3000`；开发代理转发 `/api` 到后端 `127.0.0.1:8123`。

## 功能与边界

- 基础聊天支持 SSE，恋爱问答的多轮记忆按账号、会话及模式原子保存为 JSON，保留最多 20 条上下文，支持刷新及服务重启后恢复。超级智能体仍按单次任务执行。
- 智能体逐步调用工具；普通回答完成后结束，最多执行 20 步。
- 默认注册 PDF 生成和结束工具；配置 `SEARCH_API_KEY` 后启用网页搜索。
- 中文 PDF 输出到 `tmp/pdf/`，通过 `/api/files/文件名.pdf` 下载。
- Turing 已启用 RAG：本地多语种 MiniLM 模型生成 384 维向量，分块索引保存在 `tmp/rag/`，知识库变化后自动重建。检索与建索引不调用付费模型，生成答案仍使用已有聊天模型。恋爱问答页默认选择知识库模式，回答提示包含来源文件与资料编号。
- Turing 已启用 MCP：通过 loopback SSE 连接独立服务，提供列出/读取知识库文件和时区时间查询；超级智能体可自动调用，恋爱问答页也可选择 MCP 模式。图片搜索须另配 `PEXELS_API_KEY`。云知识库和 PgVector 仍未启用。
- Turing 已按维护者要求启用通用终端：以服务账号权限执行 Linux shell 命令，默认工作目录为 `tmp/terminal/`，30 秒超时，最多返回 32768 个字符。工作目录不是权限沙箱。通用文件/网页下载工具仍由独立的 `EXTENDED_TOOLS_ENABLED` 控制。
- Turing 部署入口使用邀请制账号登录，后端同时校验代理注入的内部令牌与用户身份，避免绕过入口。用户资料按账号隔离，通用终端仅管理员可用；不要分享管理员密码。

## 构建和验证

```bash
mvn -f yangbin-image-search-mcp-server/pom.xml -DskipTests package
mvn -Dtest=DeploymentSmokeTest,EnabledFeaturesTest,TerminalOperationToolUnitTest,InternalApiTokenFilterUnitTest,WorkspaceValidationTest,KnowledgeDocumentsTest,TenantToolTest,PersistentMemoryTest,PlanningEvidenceTest,MultilingualPdfTest package
cd yangbin-ai-agent-frontend && npm ci && npm run build
```

当前 CI 共 38 项 Java 与 20 项 Python 测试，使用本地模拟模型/数据服务，不调用收费 API；覆盖无数据库启动、兼容模型对话、SSE 结束、中文 PDF 下载、RAG 证据注入、真实 MCP 进程工具调用、终端超时/输出限制及内部访问校验。原教学测试可能调用真实模型、数据库或第三方服务，不应直接对生产配置执行全部测试。

[部署与运维说明](deploy/turing/README.md) · [来源说明](NOTICE.md)

## 工作台扩展

- `/knowledge`：上传 UTF-8 Markdown/TXT 或含文本的 PDF（单文件 1 MB、最多 100 页、解析后 60000 字符），预览、重新索引和删除上传文档。内置文档只读。扫描 PDF 需先 OCR。
- `/mcp`：查看发现的工具和参数定义，测试内置文档列表/读取及时间查询，记录真实结果、成功状态与耗时。手动测试历史保留 50 条，重启后清空；不会暴露通用终端的手动执行接口。
- `/planner`：输入城市、日期、两人总预算、时间、交通和偏好，生成方案、修改并保留版本、导出中文 PDF。服务端检查金额与时间顺序；并发修改旧版本返回冲突。
- 恋爱问答与超级智能体显示真实执行事件。RAG 显示实际召回的片段与相似度；MCP/智能体显示实际工具参数、结果摘要和耗时。执行记录不是模型推理过程。
- 知识库页包含固定 6 题 Hit@3 测试，仅检测预期文件是否进入前三项，不等于答案正确率，也不调用聊天模型。

上传文档存于 `tmp/knowledge/`（可用 `app.rag.upload-dir` 覆盖），重启后重新加载并索引；方案各版本存于 `tmp/plans/`（`app.plans.dir`）。部署升级保留 `tmp/`，备份时应包含这两个目录。上传文档用于 RAG；独立 MCP 文档工具读取 `KNOWLEDGE_DIR` 指定的内置 Markdown 目录。

当前部署支持邀请制账号与数据隔离。已增加 Open-Meteo 天气预报及 Photon / OpenStreetMap 候选地点；商家实时价格、路线、当天营业状态与 Pexels 图片搜索尚未接通；规划费用是估算，不能作为已验证的地点或价格。通用终端仍以服务账号运行，不能当作隔离沙箱；普通用户无法调用通用终端；管理员终端仍需谨慎使用。混合检索与重排留作后续评测驱动的优化。

验证命令：

```sh
mvn -B -ntp -f yangbin-image-search-mcp-server/pom.xml -DskipTests package
mvn -B -ntp -Dtest=DeploymentSmokeTest,EnabledFeaturesTest,TerminalOperationToolUnitTest,InternalApiTokenFilterUnitTest,WorkspaceValidationTest,KnowledgeDocumentsTest,TenantToolTest,PersistentMemoryTest,PlanningEvidenceTest,MultilingualPdfTest package
python -m pip install -r deploy/turing/requirements.txt
python -m unittest discover -s deploy/turing -p "test_*.py"
```

## 邀请制账号系统

- `/login` 登录，`/register` 使用邀请码注册，`/account` 修改密码。管理员还可创建/撤销邀请码、停用/启用普通账号。
- 首次启动将部署环境 `ACCESS_USER` / `ACCESS_PASSWORD` 导入为管理员（固定内部 ID `admin`）。以后重启不会重置已修改的密码；原有文档、方案与 PDF 只归管理员访问。
- 账号、邀请码哈希与会话存于 `tmp/auth/accounts.sqlite`。密码用独立随机盐和 scrypt 哈希；会话使用随机 HttpOnly / SameSite=Strict Cookie，有效期 24 小时。退出、改密或停用会撤销相应会话。不要删除此数据库，否则会重新初始化管理员。
- 私有文档的向量检索在查询阶段按归属过滤，内置资料共享；方案、下载、聊天记忆和 MCP 测试历史按账号隔离。流式聊天与后台工具调用显式传递身份。普通用户只获得知识检索、内置 MCP 时间/文档工具、PDF 和终止工具；终端及其他文件/网络工具仅管理员可用。
- 代理丢弃客户端提供的身份头，以已认证会话重新注入身份；后端要求代理密钥和合法身份。前端路由跳转仅用于体验，数据权限由服务端校验。登录有尝试次数限制，写操作要求同源和 CSRF 校验。
- 当前 Turing 入口是内网 HTTP。公网使用须配置 HTTPS，并设置 `SESSION_COOKIE_SECURE=true`；不要直接暴露 Java/MCP/embedding 端口。若前置 TLS 代理，请同时正确处理应用的来源地址和协议。
- 备份 `tmp/auth/`、`tmp/knowledge/`、`tmp/plans/`、`tmp/pdf/` 与私有部署配置。测试脚本通过正常登录接口验证；管理员改密后，应使用新口令执行运维验收，环境初始口令不会覆盖数据库。

## 持久会话与规划依据

- 恋爱问答页提供历史会话、新建、删除。普通聊天 / RAG / MCP 上下文互相独立，按账号隔离；聊天窗口最多保留 20 条消息，不是完整无限聊天档案，也不是自动提炼的跨会话长期记忆。旧版仅在内存中的会话无法追溯恢复。
- 数据目录 `tmp/chat-memory-v2/`（`app.memory.dir` 可改），采用临时文件 + 原子替换；单实例内阻止同一流式会话并发生成，生成中禁止删除。多实例部署需改用共享数据库与分布式并发控制。备份时加入此目录。
- 设置 `PLANNING_LIVE_ENABLED=true` 启用规划依据。`/planner` 可先点“查询天气与地点依据”；生成/修改也自动查询并把快照保存在该版本。旧版本不回填最新天气。
- 当前限定中国城市：Open-Meteo / GeoNames 定位，优先城市级同名记录；仍有歧义时不猜测坐标。天气按城市时区匹配日期，仅接受今天至未来 15 天的预报，超范围明确不可用。
- 地点使用 Photon 查询 OpenStreetMap 的咖啡馆、餐厅、公园、博物馆候选，范围为城市定位点周边约 3 公里，最多 12 个；不是用户当前位置，不提供路线规划。显示地图来源、地址及来源可用时的营业时间；地图数据可能过时，不能当作实时商家确认。
- 相同城市/日期缓存 10 分钟，最大 128 项。外部服务失败时只保留可用依据并提示；不会把缺失天气当成晴天。对外仅发送城市/坐标与天气参数，不发送对话、私有知识库或个人偏好。
- 模型返回的 `placeId` 必须来自实际候选集合，否则拒绝保存；UI 和 PDF 显示服务端保存的来源快照。模型其余文本仍可能出错，费用始终为估算。
- 仅使用免 Key 公共接口，适合低流量非商业学习演示；Open-Meteo 免费接口有非商业使用限制，Photon 无可用性保证。公开运营需选用适当套餐或自建服务。目前未接入高德 Key。
- 数据源文档：[Open-Meteo](https://open-meteo.com/en/docs)、[GeoNames 定位](https://open-meteo.com/en/docs/geocoding-api)、[Photon](https://github.com/komoot/photon/blob/master/docs/api-v1.md)、[OpenStreetMap 署名](https://www.openstreetmap.org/copyright)。
- 可配置服务端地址：`app.planning.geocoding-url`、`app.planning.forecast-url`、`app.planning.places-url`。这些地址仅由运维配置，不接受浏览器传入 URL。

[2026-10-16 持久会话与实时规划实际验收](docs/MEMORY-LIVE-VALIDATION.md)

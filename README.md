# yangbin AI Agent

由 [BStronger1](https://github.com/BStronger1) 维护的 AI 智能体学习项目，包含恋爱问答、多轮对话、ReAct 工具调用和中文 PDF 生成。基于教学项目改编，来源见 [NOTICE.md](NOTICE.md)。

## 技术栈与模块

- Java 21、Spring Boot 3.4.4、Spring AI 1.0、Vue 3、Vite。
- 根目录：后端，包名 `com.yangbin.yangbinaiagent`。
- `yangbin-ai-agent-frontend/`：网页界面。
- `yangbin-image-search-mcp-server/`：可选的 Pexels 图片搜索 MCP 教学模块。
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

- 基础聊天支持 SSE，多轮对话记忆保存在进程内，重启后清空。
- 智能体逐步调用工具；普通回答完成后结束，最多执行 20 步。
- 默认注册 PDF 生成和结束工具；配置 `SEARCH_API_KEY` 后启用网页搜索。
- 中文 PDF 输出到 `tmp/pdf/`，通过 `/api/files/文件名.pdf` 下载。
- RAG 默认关闭；启用 `RAG_ENABLED=true` 会在启动时调用百炼 Embedding 和模型为知识库建索引，需要相应凭据。
- 云知识库、PgVector 和 MCP 保留为可选教学模块，未在默认部署中启用。PgVector 仍须自行配置数据源及其配置类。
- 服务器默认关闭通用文件操作、任意网页下载和 shell 工具；隔离环境中可通过 `EXTENDED_TOOLS_ENABLED` / `TERMINAL_ENABLED` 明确启用。
- Turing 部署入口使用个人访问口令；它不是多用户系统，不做账号间数据隔离。

## 构建和验证

```bash
mvn -Dtest=DeploymentSmokeTest package
cd yangbin-ai-agent-frontend && npm ci && npm run build
```

`DeploymentSmokeTest` 使用本地模拟模型服务，不调用收费 API，覆盖无数据库启动、兼容模型对话、SSE 结束、智能体工具执行及中文 PDF 下载。原教学测试可能调用真实模型、数据库或第三方服务，不应直接对生产配置执行全部测试。

[部署与运维说明](deploy/turing/README.md) · [来源说明](NOTICE.md)

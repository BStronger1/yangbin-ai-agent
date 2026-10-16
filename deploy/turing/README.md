# Turing 部署

目录：`~/apps/yangbin-ai-agent`。前端入口端口 18825，后端 18126 仅监听 loopback，不依赖 root 或 Docker。

## 安装

1. 本地构建：`mvn -Dtest=DeploymentSmokeTest package`；前端执行 `npm ci && npm run build`。
2. 将 `target/yangbin-ai-agent-0.0.1-SNAPSHOT.jar` 上传为服务器目录下的 `app.jar`。
3. 将前端 `dist/` 内容上传为 `frontend/`，并上传 `deploy/turing/`。
4. 使用服务器 Python 3.12 初始化，参数 `--model-json` 是用户授权复用的私有模型配置，`--java` 为 Java 21+：

```bash
~/.local/bin/python3.12 deploy/turing/bootstrap.py \
  --model-json ~/apps/yangbin-ai-code-mother/secrets.json \
  --java ~/.local/share/recall-agent/runtime/jre21/bin/java \
  --bind 172.16.185.103
bash deploy/turing/start.sh
```

访问 `http://172.16.185.103:18825`。浏览器会要求登录，用户名 `yangbin`，随机访问密码保存在服务器 `~/apps/yangbin-ai-agent/access.txt`。模型密钥只在 `secrets.json`，权限为 0600，不进入仓库。访问密码与模型密钥是不同的凭据。

校园网入口为 HTTP；推荐通过 SSH 加密隧道访问个人实例：

```bash
ssh -N -L 18825:172.16.185.103:18825 zzl-zhouyangbin@172.16.185.103
```

随后打开 `http://localhost:18825`。外网发布须配置 HTTPS 入口。

## 运维

- 启动：`bash deploy/turing/start.sh`
- 停止：`bash deploy/turing/stop.sh`
- 状态：`tmux has-session -t '=yangbin-ai-agent'`
- 健康检查：携带访问凭据请求 `/api/health`，应返回 `ok`。
- 验收：`runtime/venv/bin/python deploy/turing/smoke.py`，会调用真实模型，产生少量用量。
- 日志：`logs/application.log` 和 `logs/service.log`。
- 备份：`secrets.json`、`access.txt`、`tmp/pdf/`；聊天记忆目前不持久化。
- 升级：停止本项目，备份 `app.jar` 与 `frontend/`，替换构建产物后启动。
- 服务由独立 tmux 会话托管，SSH 断开后继续运行；服务器重启后需重新执行启动命令。

不要重复初始化覆盖现有密钥。当前 Turing 已启用本地 RAG、MCP 和终端工具。搜索 API、Pexels 图片搜索、数据库和独立文件操作工具仍需额外配置。


## RAG / MCP / 终端运行配置

- 嵌入服务：`127.0.0.1:18128`，复用 `~/apps/agent-workbench/runtime/ai-venv` 与已有模型缓存，以离线模式加载，不启动或改动原 workbench 服务。
- MCP 服务：`127.0.0.1:18127`，运行本项目 `mcp.jar`，读取 `knowledge/*.md`。
- 后端：`127.0.0.1:18126`；代理入口维持 `172.16.185.103:18825`。
- 启动顺序为嵌入服务 → MCP → 后端 → 入口；每项依赖就绪后才启动下一项。
- `secrets.json` 中 `RAG_ENABLED`、`MCP_ENABLED`、`TERMINAL_ENABLED` 为 `true`。`INTERNAL_API_TOKEN` 只由入口代理注入，不应发给浏览器。
- 终端以 `zzl-zhouyangbin` 的系统权限执行，默认目录 `tmp/terminal/`，30 秒超时、32768 字符输出上限；不是容器或权限沙箱。不要暴露给不可信用户。
- 当前知识库来自三个内置 Markdown 文档；修改后需要重新构建后端，并同步 `knowledge/` 给 MCP。不是在线上传系统。
- 构建验证先打包 MCP 模块，再运行主项目的 12 项测试，命令见根 README。
- 升级脚本 `enable-features.py` 使用项目目录中的可信 `features.tar.gz`，备份到 `releases/before-features-*`，保留原账号密码和模型密钥。
- `FeatureVerificationMain` 是显式调用的运维入口：用实际嵌入、MCP 和终端检查检索/列出文件/读取文件/查询时间/echo，结果保存在 `logs/feature-verification.json`，不调用聊天模型。

若不需要终端，把 `TERMINAL_ENABLED` 改为 `false` 后重启；不要直接删除访问认证配置。

## 一次纠错记录

第四版将纠错预留与候选发布状态持久化于 `tmp/repairs/`（可用 `app.repairs.dir` 覆盖）。备份该目录及 `tmp/plans/`、`tmp/tasks/`、`tmp/pdf/`。不要删除记录来重置次数。每方案一次逻辑模型尝试；服务重启将 RUNNING 标记为 INTERRUPTED，不重调模型。PUBLISHING 在所属用户查询或重复提交时恢复同一候选的发布，不调用模型。记录不含密钥；新旧格式回退前须验证兼容性。详情见 `docs/REPAIR-VALIDATION.md`。

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

不要重复初始化覆盖现有密钥。默认未启用搜索 API、RAG、MCP、数据库和通用 shell/文件工具；相关功能需单独配置和验收。

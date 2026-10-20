# 自部署

## 本机试用

按README安装运行环境和 `python tools/run_local.py --build`。启动器提供Spring后端和会话网关，默认只监听127.0.0.1。可以用 `--port`、`--backend-port`避开占用端口；不会停止其他实例。

`.env`是可选的本地配置，按 `.env.example` 填写，不能提交Git。环境变量优先于文件；配置只按NAME=value读取，不执行shell代码。首次管理员账号默认admin，可以用ACCESS_USER指定；首次密码交互输入，也可由私有环境变量ACCESS_PASSWORD提供。已创建账号不会被启动器重置密码。

## 公网部署

使用独立低权限服务账号和TLS反向代理。后端与会话网关保持loopback监听，只有TLS入口对外。设置：

```dotenv
PUBLIC_ORIGIN=https://agent.example.com
SESSION_COOKIE_SECURE=true
AGENT_PRODUCTION_MODE=true
GATEWAY_TLS_MODE=reverse-proxy
PUBLIC_BIND=127.0.0.1
```

将TLS入口反向代理到 `http://127.0.0.1:18825`，保留Host，关闭流式响应缓冲并允许长请求。网关只信任显式配置的PUBLIC_ORIGIN，不信任任意X-Forwarded-Host来放宽来源检查。后端不能直接暴露公网，内部鉴权令牌不得发送给浏览器。

邀请码、账号管理、CSRF和HTTP-only会话由现有网关提供。命令工具仅在 Linux bubblewrap + prlimit 完整探测通过后启用，不满足条件时禁用且不回退宿主 shell。整个应用的容器隔离、cgroup 聚合配额及不可信多租户安全验收仍需另外完成，详见 [治理与隔离边界](PRODUCTION-GOVERNANCE.md)。内网HTTP可用于可信网络，但API Key传输不加密，公网必须使用HTTPS。

没有公网 IP 的服务器可以使用独立命名隧道，参见 [Cloudflare Tunnel 接入](CLOUDFLARE-TUNNEL.md)。它可保留现有内网入口与共享反向代理；域名、账号认证和最终 HTTPS 验收需要实际完成。

## 运行与恢复

保留 `tmp/`（账号、任务、模型密文及主密钥）与知识库数据；备份存放在限制访问的位置。升级前先备份程序、配置和数据，验证新版本可登录、旧记录可读、模型配置仍可解密、Coding依赖已就绪。代码回退不会自动回退业务数据。

`deploy/turing/update.py`是维护者专用安装脚本，包含固定个人安装路径，不是通用安装器。它会安装版本化pi运行时、验证模型配置接口和Coding能力，并为数据做备份。其他机器使用本机启动器或自己的服务管理器；不要照搬个人服务器路径。

RAG/MCP/NarrativePPT为可选独立服务。默认试用不下载Embedding模型，不启动数据库/Redis，也不调用付费模型，直到用户明确提交模型任务。

# Cloudflare Tunnel：独立公网入口

当前目标为 `https://www.yangbinai.homes`。校园服务器无需公网 IP，也无需开放入站 80/443。保留内网 `:18825` 与服务器已有 Caddy；新网关仅监听 `127.0.0.1:18826`，由本项目独立命名 Tunnel 转发。

## 1. 用户账户与域名操作

1. 在 [Cloudflare 控制台](https://dash.cloudflare.com/) 添加根域名 `yangbinai.homes`，选择 Free，检查导入的全部 DNS 记录，保留其他业务的 A/AAAA、MX、TXT。不要把校园内网 IP 添加为公网 A 记录。
2. 按 Cloudflare 的 [完整 DNS 设置流程](https://developers.cloudflare.com/dns/zone-setups/full-setup/setup/) 获取该 zone 分配的两个 nameserver。若注册商开启 DNSSEC，先按该流程关闭旧 DNSSEC/移除旧 DS，避免迁移解析失败。
3. 在聚名网域名管理中修改该域名的 DNS 服务器为这两个精确名称。这是注册商 nameserver 设置，不是在 DNS 记录表新增 `www NS`。参见 [聚名官方帮助](https://www.juming.com/help/94.htm)；菜单名称可能随界面更新。
4. 等待 Cloudflare zone 显示 Active；之后按 Cloudflare 指引重新启用 DNSSEC。无需转移域名注册商。
5. 按 [官方命名 Tunnel 创建流程](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/) 在 Networking → Tunnels 创建本项目独立 Tunnel。界面可能随账户导航变化。不要使用服务器其他应用的 connector/token。
6. 添加 Published application 路由：子域 `www`，域名 `yangbinai.homes`，路径留空，服务类型 HTTP，服务地址 `127.0.0.1:18826`。如果 `www` 已有记录，先核实其业务用途再迁移。

Tunnel token 由用户通过服务器自己的安全终端/编辑器写入项目私有文件，权限 `0600`，不要发送聊天、提交 Git、放进命令参数或粘贴到交接文档。优先使用官方支持的 `--token-file`。用户只需反馈 zone 状态和非敏感路由字段。

## 2. 项目网关准备

先部署包含 `public_gateway.py`、`readiness.py`、生产传输校验的版本，并确保主服务已启动、账户库已初始化。项目根目录复制非敏感模板为 `public-gateway.json`，内容：

```json
{"origin":"https://www.yangbinai.homes","port":18826}
```

在项目根目录运行（完成配置后再启动）：

```bash
runtime/venv/bin/python deploy/turing/public_gateway.py start
runtime/venv/bin/python deploy/turing/public_gateway.py status
# 仅停止本项目公网入口：
runtime/venv/bin/python deploy/turing/public_gateway.py stop
```

管理器强制 HTTPS Origin、Secure Cookie、生产模式及 loopback，不会改变内网入口配置，不修改共享 Caddy，也不启动 Tunnel。独立 tmux 会话为 `yangbin-ai-agent-public`，日志为 `logs/gateway-public.jsonl`，采用内容无关的聚合指标和轮转日志。正常停止等待在途请求退出；不会强杀其他进程。

两个入口使用同一后端、内部令牌及 `tmp/auth/accounts.sqlite`。会话撤销、账号停用和邀请码事务共享；不同浏览器 origin 的 Cookie 互不替代。启动第二网关不会重置已有管理员密码。SQLite 的并发事务保护已做双实例测试；此方案限定同一安装目录和同一主机，不是分布式部署。

**限流边界：** HTTP 并发、RPM、登录速率及密码计算槽位按网关进程计算。两个入口的总容量可能达到单入口限额之和，不能声称全局限流。模型任务预算与用户归属仍由共享后端约束。内网 HTTP 入口不是互联网安全入口，仍保留其已有局域网使用边界。

发布器会先停止公网网关和主服务再备份 SQLite，避免复制期间写入；成功或回滚后仅恢复发布前已运行的公网网关，不会自动首次公开服务。两个 tmux 会话并非系统开机自启动或高可用方案，进程告警/重启策略仍需运维接入。

## 3. Connector 与验收

官方下载应校验对应 GitHub release 的 SHA-256。`cloudflared 2026.10.2` 实际 `tunnel run --help` 支持 `--token-file`。使用本项目明确的配置文件与 token 文件路径，避免自动读取同一账号其他 Tunnel 配置。示意命令中的路径均须先准备好：

```bash
runtime/cloudflared/2026.10.2/cloudflared tunnel \
  --config runtime/cloudflared/private/config.yml --no-autoupdate \
  run --token-file runtime/cloudflared/private/tunnel.token
```

`runtime/cloudflared/private/config.yml` 可只设置 `no-autoupdate: true`；路由由已认证的 Cloudflare 控制台管理。不要使用 quick tunnel 临时公开站点，不要设置跳过 TLS 校验或启用包含敏感头的 debug 日志。Connector 的持久运行、独立 metrics 端口及受控日志需在实际认证部署时配置，不能复用或终止其他项目的实例。

[官方网络要求](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/tunnel-with-firewall/)为到官方区域端点的出站 UDP 7844（QUIC）或 TCP 7844（HTTP/2），并需 DNS 与控制面访问。TCP 建连成功、UDP 回包或本地 `/api/auth/me` 成功，都不等于带认证的 Tunnel 已上线。

只有完成以下真实验证后，才能宣布公网可用：

- Cloudflare 显示本项目 connector Healthy，且 `www` 路由准确。
- 外部网络访问 HTTPS，证书链和主机名验证通过；不使用 `-k`。
- 未登录业务 API 拒绝访问；登录 Cookie 带 Secure/HttpOnly，跨来源写入被拒绝。
- 对话/工作 SSE、取消、文件上传下载、退出登录与管理员额度功能通过。
- 内网入口仍可用，其他现有域名和 Caddy 服务未受影响。

2026-10-21 的网络预检证明服务器可达两个官方区域 TCP 7844，两个区域均收到合法对应的 QUIC 版本协商回包；尚未完成本项目账号认证。普通系统 CA 的 Python TLS 探测返回证书校验失败，因此不能据此声称 HTTP/2 Tunnel 认证成功。官方 `tunnel diag` 在当前二进制中要求选择运行实例，并非预检成功证明；未采集其他应用实例的诊断资料。

凭据目录固定使用已被 `.gitignore` 的 `runtime/` 覆盖的 `runtime/cloudflared/private/`。用户在服务器终端创建目录（权限 `0700`）与 token 文件（权限 `0600`），使用不回显的交互输入或私有编辑器写入；禁止 `echo 明文token`、带明文 token 的命令和 shell 历史。不要将运行 token 放在受 Git 跟踪的文档/部署目录。`public-gateway.json` 只包含非敏感域名和端口。

更新器若从独立 `ops-m024/` 目录运行，需要将同版本的 `update.py`、`readiness.py`、`public_gateway.py` 放在同一目录。更新器使用固定安装根 `~/apps/yangbin-ai-agent`，并显式调用 `start_public(ROOT)`；不会将临时脚本目录当作安装根。正式的公网网关脚本则始终由安装目录 `deploy/turing/public_gateway.py` 启动。

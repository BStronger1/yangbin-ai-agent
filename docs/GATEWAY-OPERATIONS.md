# 网关准入、追踪与 TLS

网关在正常会话认证和 CSRF 校验后进行 HTTP 准入。它不读取提示词来判断成本，也不自动重放任何业务请求。后台 Coding / 伙伴任务结束前的配额由后端负责，HTTP 请求结束不表示后台任务结束。

## 默认限制

| 环境变量 | 默认值 | 范围 |
| --- | ---: | --- |
| `GATEWAY_MAX_CONCURRENT` | 64 | 单网关全部业务请求 |
| `GATEWAY_USER_CONCURRENT` | 12 | 单账号业务请求，包括 SSE |
| `GATEWAY_MODEL_CONCURRENT` | 8 | 模型/工具执行入口 HTTP 请求 |
| `GATEWAY_USER_MODEL_CONCURRENT` | 2 | 单账号模型/工具执行入口 |
| `GATEWAY_USER_RPM` | 240 | 单账号每 60 秒业务请求 |
| `GATEWAY_USER_MODEL_RPM` | 20 | 单账号每 60 秒模型/工具提交 |

所有限制必须是 1–100000 的整数，0 不表示无限。过频返回 `429 / RATE_LIMITED / Retry-After: 60`，并发满返回 `503 / CAPACITY_LIMIT / Retry-After: 2`，立即拒绝、不排队。登录保留原有 IP/用户名限流，并在两个密码哈希槽都占用时拒绝新增哈希任务。取消和审批不消耗模型入口额度，但仍受普通业务请求保护。MCP 手动调用与旧 Manus/Love SSE、伙伴 messages/jobs、Coding turns、任务 start/retry、方案生成/修订/修复和 PPT API 写入均分类准入。

计数保存在单进程内存，重启重置；横向扩容前须引入共享计数或按实例规划容量。请求额度不等于 Token 或费用预算。

## 请求追踪与运维接口

- 每个网关请求生成全新 32 位小写十六进制 `X-Request-Id`，返回给客户端并传给后端。客户端提供的 Request ID、traceparent/tracestate、baggage 和 Forwarded 类头会剥除；身份只来自已验证会话。
- `GET /api/ops/health`：登录管理员专用，返回网关状态、TLS 配置、准入限额和活动请求。它不是后端、MCP 或模型健康检查。
- `GET /api/ops/metrics`：登录管理员专用，返回固定路由类别及状态类别计数、拒绝数、最近每类别 512 个完成请求的 P95。延迟窗口和样本量明确显示，不充当长期 SLO 或承载能力结论。
- `logs/gateway.jsonl`：JSON 事件日志，单文件 2 MB，保留 3 个轮转文件；只记录 UTC 时间、请求 ID、固定路由类别、HTTP 状态和耗时。不会记录 URL/查询参数、账号、IP、模型/工具正文、Cookie、密钥或异常详情。部署应限制日志目录访问。
- Java Advisor 与工具追踪日志仅保存状态/耗时/调用编号。用户明确请求的工具结果仍能返回到授权会话，不被写入诊断日志。

暂未接入外部监控、告警发送和分布式 Trace collector。不要把运维接口存在当作完成了告警值班和故障演练。

## TLS 部署准备

现有内网试用入口可继续 HTTP；公网生产部署应设置 `AGENT_PRODUCTION_MODE=true`。生产模式启动要求同时设置 HTTPS 的 `PUBLIC_ORIGIN`、`SESSION_COOKIE_SECURE=true` 和有效 TLS 模式，缺失则启动失败。

**自带 TLS：** 设置 `GATEWAY_TLS_MODE=direct`、`GATEWAY_TLS_CERT` 和 `GATEWAY_TLS_KEY` 指向运维提供的有效证书链和私钥。启动加载失败则拒绝监听；最低 TLS 1.2。监控/启动探针也须使用 HTTPS 并正常校验证书。

**独立 TLS 反向代理：** 设置 `GATEWAY_TLS_MODE=reverse-proxy`、`PUBLIC_BIND=127.0.0.1`（或 `::1`），由已安装的 Nginx/Caddy 等终止 TLS。生产模式拒绝非 loopback 直连；无需信任外部 `X-Forwarded-*` 来决定身份或 origin。反代应固定上游，清除外部身份头并正确传递流式响应。所有后端端口仍须 loopback。

程序不能证明反代实际启用了证书或证书被浏览器信任。必须从预期用户网络验证 DNS、证书域名/链/有效期、HTTPS 登录 Secure Cookie、SSE、HTTP 入口关闭/跳转和后端端口不可直连后，才可宣称上线 HTTPS。仓库不生成“伪可信”证书、不提交私钥，也不因打开生产开关自动安装任何证书。

## 直连公网 / Caddy 的备选配置

当前校园无公网 IP 的部署采用 [Cloudflare Tunnel 独立入口](CLOUDFLARE-TUNNEL.md)：`www.yangbinai.homes` → loopback `18826`，保留内网 `18825` 和已有 Caddy。以下 apex/Caddy 方案是备选，不应用于当前 Tunnel 部署。

目标域名为 `yangbinai.homes`。候选站点片段在 [`deploy/turing/Caddyfile.yangbinai.example`](../deploy/turing/Caddyfile.yangbinai.example)，仅供现有反向代理管理员合并。它不自动安装 Caddy、不占用已使用的 80/443，也不覆盖共享服务器配置。

接入前由 DNS/网络管理员确认该域名的权威解析与真实公网入口。不能把 Turing 的 RFC1918 内网地址或本机 DNS 代理返回的基准测试地址当作公网 DNS 目标。确认域名路由、证书签发及反向代理权限后，在项目私有运行配置中设置：

```text
PUBLIC_ORIGIN=https://yangbinai.homes
SESSION_COOKIE_SECURE=true
AGENT_PRODUCTION_MODE=true
GATEWAY_TLS_MODE=reverse-proxy
PUBLIC_BIND=127.0.0.1
PUBLIC_PORT=18825
```

这组值不含密钥；真正写入前必须完成配置备份、现有站点保留和回退准备。切换后公网经 TLS 入口访问，旧的局域网 `:18825` 直连会关闭。Caddy 管理员应先使用其现有二进制验证合并后的完整配置，再按原部署方式 reload，不能另起一个未知版本覆盖共享入口。需要保持后台端口仅本机可达，并检查共享代理自身的访问日志不会记录含消息的 URL、Cookie 或认证头。

模板保留 Caddy 默认 SSE 即时转发行为，没有设置负值 `flush_interval`，避免该选项改变客户端断连后的上游取消行为。证书自动签发需要 DNS 与外部网络条件满足，不是加入域名文本就已获得可信 HTTPS。参见 [Caddy 反向代理流式说明](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#streaming) 与 [自动 HTTPS 条件](https://caddyserver.com/docs/automatic-https)。

`update.py` 现在通过 `readiness.py` 做本地网关验收：普通/反代模式使用实际绑定地址；direct TLS 模式连接本地监听端口，同时以 `PUBLIC_ORIGIN` 域名进行 SNI、证书链和主机名校验。使用系统信任库，不禁用验证，也不失败回退 HTTP；不要求服务器自身通过公网 DNS 绕回本机。反代模式的本地 HTTP 就绪不证明外部 HTTPS，因此仍需独立从用户网络验收域名入口。

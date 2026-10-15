# 高德规划依据接入：2026-10-16 实际验收

## 已实现

后端从私有环境读取 `AMAP_API_KEY`，通过高德 Web 服务进行地理编码和周边地点搜索。优先使用高德候选；无 Key 或高德调用失败时保留 Photon / OpenStreetMap 回退并显示原因。高德成功但候选为空时如实显示空结果，不混入其他来源。

高德定位与地点标注 GCJ-02，链接采用无 Key 的 POI ID 或明确的高德坐标参数。天气仍使用独立的 GeoNames / Open-Meteo WGS84 定位。来源快照随方案保存，前端与 PDF 标注实际来源，旧版快照仍可读取。

## 本次验证

- 本地 44 项 Java 测试通过、生产 JAR 打包和 Vue 构建通过。新增 6 项覆盖坐标与无密钥链接、白名单拒绝回退和错误脱敏、免费定位失败时高德独立工作、无效/超范围/重复地点过滤、空候选和旧快照兼容。成功高德响应使用本地 fixture，不是真实高德地点验收。
- Turing 私有配置权限检查通过；更新后正常登录、天气返回和 10 个 Photon / OpenStreetMap 候选通过。API 响应及浏览器 DOM 均未包含密钥。
- 浏览器检查：旧方案读取、实时依据、来源/回退说明、390px 手机布局通过，无 JavaScript 错误。
- 本轮未新增真实模型请求；此前真实模型、会话重启和 PDF 验收见 MEMORY-LIVE-VALIDATION.md。不能将之前结果算作本轮高德验收。

## 当前阻塞与复验

**真实高德地理编码返回 `10005 / INVALID_USER_IP`，高德地点尚未通过线上验收。** 需在高德控制台将服务器实际公网出口加入此 Web 服务 Key 的 IP 白名单；不要填写内网服务器地址。学校网络可能存在多个出口，以高德实际请求路径为准。本轮未修改高德账户的白名单。

白名单修改后，在 `/planner` 点击“查询天气与地点依据”；已有失败结果缓存 1 分钟。或在服务器执行：

```sh
python3 ~/apps/yangbin-ai-agent/deploy/turing/amap-smoke.py
```

输出应为 `amap_live: true`，否则不能宣称高德已可用。脚本仅打印状态、数量，不打印密钥。浏览器复验使用 `deploy/turing/amap-browser.py`，需部署环境的 Playwright 运行时。

运维证据：服务器 `logs/amap-verification.json` 与 `logs/screenshots/amap-planning-mobile.png`；本地 `tmp/amap-evidence/`；部署回滚包 `releases/before-workspace-5c7e7503`。密钥不在仓库或本文档。

# 高德规划依据接入：2026-10-16 实际验收

## 已实现

后端从私有环境读取 `AMAP_API_KEY`，通过高德 Web 服务进行地理编码和周边地点搜索。优先使用高德候选；无 Key 或高德调用失败时保留 Photon / OpenStreetMap 回退并显示原因。高德成功但候选为空时如实显示空结果，不混入其他来源。

高德定位与地点标注 GCJ-02，链接采用无 Key 的 POI ID 或明确的高德坐标参数。天气仍使用独立的 GeoNames / Open-Meteo WGS84 定位。来源快照随方案保存，前端与 PDF 标注实际来源，旧版快照仍可读取。

## 本次验证

- 本地 44 项 Java 测试通过、生产 JAR 打包和 Vue 构建通过。新增 6 项覆盖坐标与无密钥链接、白名单拒绝回退和错误脱敏、免费定位失败时高德独立工作、无效/超范围/重复地点过滤、空候选和旧快照兼容。成功高德响应使用本地 fixture，不是真实高德地点验收。
- Turing 私有配置权限检查通过；更新后正常登录、天气返回和 10 个 Photon / OpenStreetMap 候选通过。API 响应及浏览器 DOM 均未包含密钥。
- 浏览器检查：旧方案读取、实时依据、来源/回退说明、390px 手机布局通过，无 JavaScript 错误。
- 本轮未新增真实模型请求；此前真实模型、会话重启和 PDF 验收见 MEMORY-LIVE-VALIDATION.md。不能将之前结果算作本轮高德验收。

## 白名单修正后的复验

首次请求返回 `10005 / INVALID_USER_IP`，上述回退测试记录保留。随后用户自行修改高德控制台白名单，复验结果：

- 线上规划服务真实返回上海 **12 个高德候选**，`amap_live=true`、`fallback_live=false`，天气可用。所有候选使用高德来源链接，坐标标记 GCJ-02，响应不含密钥。
- 再次浏览器检查：旧方案正常，新查询显示高德来源，手机布局通过，无 JS 错误，DOM 不含密钥。
- 代码提交 `e3800de` 的 [CI](https://github.com/BStronger1/yangbin-ai-agent/actions/runs/38781116139) 成功，包含 44 项 Java、20 项 Python 及前端构建。本次未重复本地全套测试，也未新增模型生成或 PDF 验收。

网络诊断边界：独立 Python 直连探针在同一轮仍返回 10005，且未检测到环境代理；原因尚未确认，不能据此归因代理或缓存。应用端使用另一组城市/日期（上海市、后天）重新查询，仍取得 12 条高德地点和天气，排除了只复用上一组应用缓存的情况。当前成功结论限定实际 Java 规划服务路径；探针状态与应用状态分别保留，后续若应用回退再调查出口差异。

白名单修改后，在 `/planner` 点击“查询天气与地点依据”；已有失败结果缓存 1 分钟。或在服务器执行：

```sh
python3 ~/apps/yangbin-ai-agent/deploy/turing/amap-smoke.py
```

输出应为 `amap_live: true`，否则不能宣称高德已可用。脚本仅打印状态、数量，不打印密钥。浏览器复验使用 `deploy/turing/amap-browser.py`，需部署环境的 Playwright 运行时。

运维证据：服务器 `logs/amap-verification.json` 与 `logs/screenshots/amap-planning-mobile.png`；本地 `tmp/amap-evidence/`；部署回滚包 `releases/before-workspace-5c7e7503`。密钥不在仓库或本文档。


## 2026-10-16 北京天气失败缓存修复

用户报告北京天气不可用。本次复现同日“北京”读到天气失败快照，而“北京市”重新查询成功；直接天气接口返回完整当日预报。确认原实现将暂不可用结果与正常结果同样缓存 10 分钟，放大了单次请求失败。首次请求失败的网络/上游原因未确认，不将其归因高德 Key。

修复提交 `ea1fc5d`：天气或地点暂不可用时不写入查询缓存，用户可立即重试；成功结果仍缓存。前端新增失败重试按钮，明确区分本次查询和历史快照，切换方案或新建时清理旧预览。历史版本的数据不被覆盖。

本次本地 45 项 Java 测试与生产构建通过，其中新测试模拟天气失败→同城同日立即恢复→成功结果继续缓存。前端构建通过。Turing 已部署，回滚版本 `releases/before-workspace-71f9caa2`。

线上浏览器先模拟一次失败响应，再通过重试按钮访问真实后端：北京 2026-10-16 返回雨、13.2–18.1℃、最大降水概率 47%，12 条高德候选；历史快照标注、390px 布局、无 JS 错误通过。天气数字只代表本次查询时的预报。没有新增模型调用或生成方案。

证据：`logs/beijing-weather-verification.json`、`logs/screenshots/beijing-weather-fixed.png`；复验脚本 `deploy/turing/weather-browser.py`，本地副本 `tmp/weather-fix-evidence/`。

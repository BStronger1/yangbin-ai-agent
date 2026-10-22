# 站间步行路线：使用与本轮验收

更新于2026-10-17，M006；walking-routes-v1，本地实现，未部署。

## 使用

在“约会规划”保存方案或“任务”已生成方案中点击“查询步行路线”。系统按当前版本的相邻站点查询，显示道路距离、预计步行时间和查询时间。不会自动请求，不调用生成模型。

- 复用服务端 app.planning.live-enabled、AMAP_API_KEY / app.planning.amap-key 和 app.planning.amap-url 配置；Web 服务 Key 必须有步行接口权限。没有配置时明确不可用。
- 仅使用该版本保存的高德地点与有效 GCJ-02 坐标。旧坐标无坐标系、Photon/WGS84、重复地点编号或缺地点时不猜填。两站同一地点跳过站间查询，内部移动仍未核验。
- 使用高德 v3 步行路径接口，传经纬度（经度在前、6位小数）；读取道路距离（米）和预计时间（秒），取第一条返回路线。[官方接口说明](https://developer.amap.com/api/webservice/guide/api/direction)（2026-10-17核对）。
- 每次最多11段、每段最多一次请求、单段最多4秒、整次网络等待预算15秒；不自动重试，不跟随重定向，响应体上限512000字节。慢响应体同样受超时控制。
- 只有原交通方式恰为“步行”才比较站点开始时间：预计步行超过间隔时提醒；否则显示扣除步行后剩余的时间，并提示还要容纳上一站活动、进出场和缓冲。没有停留时长，绝不判定“保证可行”。
- 公交、自驾、打车或混合交通只展示步行参考，不据此判断原行程超时。地图估时不是出行当天保证，营业/预约/价格仍未核验。

## 接口与保存边界

GET /api/ai/plans/{id}/{version}/routes（后端映射不含 /api）。

PlanRouteController 先使用 PlannerService.get 校验账号与确切版本，再调用 PlanRouteService。报告含 planId、planVersion、规则版本、查询时间、原地点快照时间、requestedTransport、upstreamRequests 和各路段结果。缺失时间/距离用 null，不用0伪装成功。

AVAILABLE / PARTIAL / UNAVAILABLE / NO_LEGS 是报告覆盖状态，不是行程可行性。具体路段保留 DISABLED、NOT_CONFIGURED、MISSING_COORDINATES、SAME_PLACE、TIME_BUDGET_EXHAUSTED 和 UNAVAILABLE。供应商原始错误、请求URL及Key不返回。

这是手动即时参考：没有持久化路线结果或缓存，每次点击重新请求；切换页面后结果不保留。网络查询可能消耗地图接口配额。旧方案、原证据、PDF、来源核验与一次纠错账本保持原状；新路线不自动成为旧断言的支持依据。未实现公交/驾车路线、地图轨迹、停留时长、自动重规划和生成前路线约束。

前端 PlanRoutes.vue 接入 Planner.vue、Tasks.vue；切换账号、方案或版本清除结果并取消旧请求，迟到响应及版本不符响应不显示。失败时清除旧路线，允许手动重试。

## 2026-10-17 实际验证及停止条件

本轮只验证新功能主流程、异常/隔离、关联回归与界面。它们通过后结束本轮，不把通用语义研究当作开发新功能的前置门槛。

- Java 50项通过：PlanRouteServiceTest 8、PlanningEvidenceTest 12、SourceClaimReviewTest 11、PlanRepairServiceTest 16、WorkspaceValidationTest 3。地图为本机 HTTP 模拟服务；覆盖米/秒/经纬度顺序、保守时间判断、混合交通、无坐标/重复坐标/旧记录、同地点、错误/超大体/重定向/慢响应体、部分失败、11段上限、确切版本与账号隔离。未授权查询发起地图请求0，原方案文件字节不变，无模型调用。
- 前端 npm run build 通过。
- 14项浏览器组件检查通过：手动触发、busy重复点击、耗时与超时提醒、版本切换清空、迟到版本与账号响应取消/忽略、服务失败可见、错误版本拒绝、公交只作参考、部分失败保留成功段、错误后恢复、1280/390宽度无横向溢出、无未捕获JS异常。使用真实 Vue 组件及模拟API，返回夹具来自上述Java测试；不是完整HTTP登录链路或真实地图联调。
- 手机截图已人工查看；本地临时页面和Vite进程在验收后清理，夹具/脚本/截图保留在证据目录。
- 证据：intern/tmp/route-evidence/，含java.log、frontend-build.log、component-browser-checks.json、两种尺寸截图、example.json、verification.json。
- 本轮无真实高德路线请求、无模型请求、无部署或远程CI。仅修改了CI测试清单；未重跑整套Java/Python/4个真实任务。现有冻结评测和用户确认标签不变。
- 源码基线f66db28，前几轮与本轮修改均未提交/推送/部署。历史部署9cc73b0不能当作新路线功能已上线。

## 后续

本轮停止条件已满足，可以继续产品功能。建议下一增量为用户确认后的有限行程调整：显式停留时间、路线依据冻结、独立新版本及有限尝试预算，独立于旧“一次文字纠错”账本。发布前再做真实地图权限/路线烟测和账号HTTP联调。外部24例通用语义适配、独立中文标签与4个真实任务仍为待办，不要求先完成才能写新功能。

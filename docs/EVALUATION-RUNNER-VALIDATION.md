# 第3步离线执行验收

日期：2026-10-16。代码HEAD f66db28及工作区未提交增量；业务主源码沿用第1步，不修改来源规则。历史部署9cc73b0未更新，本轮未核验线上健康。

## 实际结果

- 最终runId：dfae4bfa-0205-4b01-b9c7-74925d2a38a4。
- datasetHash：a1c7eda476c3bfedd5cea07bc1851ae920d91883dd39a2995c2b8b00ceef4974；冻结集及作者参考文件未修改。
- 12/12固定输出已回放：G06未知地点ID、G10超预算被结构校验拒绝；4/12案例有warning（G06/G10/G11/G12），其余无warning。
- 8/8离线故障场景通过16项约定断言；不是模型成功率。原始检查点/方案/纠错ledger/合成PDF保存在每个Fxx目录。
- L01–L04共4个真实任务模板未运行，不计入通过分母。
- 12例×3维度=36个AI辅助标签已填；人工完成0，模型准确率/误报率/漏报率不可用。
- 新上游模型调用0，外部来源调用0；替身调用与规划替身计数逐例记录，Token/费用null。
- 56项Java定向回归通过，包含执行元数据3、任务14、工作流集成9、确认9、规则4、纠错16、冻结执行器1。最后1项运行20个离线案例，不把20案例再加进JUnit分母。
- 74项Python通过（原55+新增19），包括助手/人工来源隔离、指纹绑定、故障失败保留、未执行分母及HTML转义。
- 桌面1280宽截图通过；手机390宽使用真实CDP视口复验，innerWidth=scrollWidth=390；浏览器筛选24→8故障/4未运行，搜索G04→1。
- 未重跑前端应用构建、完整115项Java、20项部署Python、远程CI或真实模型。只读HTML是新增评测产物，不是已接入线上工作台的新页面。

## 保留负例

|案例|结构|warning数|AI来源支持建议|后续复核|
|---|---|---|---|---|
|G01|PASS|0|SUPPORTED|—|
|G02|PASS|0|SUPPORTED|—|
|G03|PASS|0|SUPPORTED|—|
|G04|PASS|0|UNSUPPORTED|待复核：辅助判断不支持，但规则未告警|
|G05|PASS|0|UNSUPPORTED|待复核：辅助判断不支持，但规则未告警|
|G06|FAIL|1|UNSUPPORTED|—|
|G07|PASS|0|UNSUPPORTED|待复核：辅助判断不支持，但规则未告警|
|G08|PASS|0|UNSUPPORTED|待复核：辅助判断不支持，但规则未告警|
|G09|PASS|0|SUPPORTED|—|
|G10|FAIL|1|SUPPORTED|—|
|G11|PASS|2|UNSUPPORTED|—|
|G12|PASS|1|UNSUPPORTED|—|

G04确定整个下午无雨、G05咖啡馆古代青铜器展、G07分钟级云层变化与拍照窗口、G08沿用旧宣传单的确定票价，均没有warning。它们是辅助判断与当前规则的分歧线索，不是经人工金标准测得的漏报率。

G12虽因营业核实断言触发warning，不能据此认为同句步行6分钟和免预约全部已被检测。G10的预算warning与其来源支持建议不冲突，报告将结构和来源维度分开。

所有固定案例另有SNAPSHOT_TIME_INVALID信息项：合成快照在2026-11-14，本轮运行于10月16日。此信息项不计warning；未改冻结日期消除提示。规则使用系统时钟，时间项会随重跑日期改变。

## 证据与复现

项目内最终记录：target/evaluation/dfae4bfa-0205-4b01-b9c7-74925d2a38a4/observations.json、report/report.json、report/cases.csv、report/index.html。完整本地归档：intern/tmp/evaluation-offline-evidence/dfae4bfa-0205-4b01-b9c7-74925d2a38a4/；同目录上层的verification.json记录文件hash，另有Java/Python日志、browser-checks.json、桌面/手机截图。

一键入口见[EVALUATION-RUNNER](EVALUATION-RUNNER.md)。原始运行保存源码指纹并验证执行期间不变；最后修复看板手机换行仅改Python渲染器，报告另外记录rendererFileHash，不将当前渲染器伪装成Java运行时快照。

初跑4baea249-6ed2-41b6-befc-ccde36f4f659通过，但重启模拟当时允许原模拟进程完成后再恢复检查点；已加强为Error模拟结果丢失且断言尚无方案后重建。最终采用加强后的运行。一键入口另有8689a80a-f02c-47d2-9ea2-fa7d344371c8通过。各原始运行保留，不覆盖。

浏览器初验发现长英文标记撑宽页面；添加统一盒模型及长词换行后390宽通过。一次浏览器复验读到旧调试端口失败，改用每次独立临时profile后通过；没有因此访问上游或改动业务数据。

## 遗留

真实任务适配、真实模型费用/Token采集、人工或外部人工基准接入、来源语义核验尚未完成。F03为本地检查点/服务重建模拟；F07为服务/控制器身份隔离，不替代OS重启和HTTP认证验收。所有工作仍未提交、推送、部署；岗位专项建议文件保留。

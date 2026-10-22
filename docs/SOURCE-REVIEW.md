# 第4步：逐条来源核验初版

当前实现是travel-claims-v1：面向已保存中文出行快照的有限断言/证据检查，不是通用LLM语义裁判。原grounding-v2来源报告和一次纠错准入不改；此增量提供人工复核线索，尚未上线。

## 用户可见行为

已保存方案和任务页面新增“逐条来源核验”，点击“检查此版本”后显示：
- 可疑原文，分别标记缺少依据或与保存快照不一致。
- 为什么当前天气/地点记录无法支持该断言。
- 全部已识别、条件/未确认说明和无法判断的文本。

切换账号、方案或版本会取消旧请求、清空结果；迟到响应不能进入新账号/版本。请求失败可重试。结果不自动修改原文，也不代表已核实实际营业、路线或票价。

只读API：GET /api/ai/plans/{id}/{version}/source-review（后端映射不含代理/api前缀）。先用PlannerService.get核对owner和版本，之后仅计算报告，不访问模型/外部来源或写文件。响应绑定planId、planVersion、输入hash与核验版本。

## 判断与边界

逐分句处理天气精度、明确日预报/降水概率、实际价格、营业/预约、路线时间/距离和具体展览。每条记录包含字段JSON Pointer、UTF-16起止位置、原文、判定理由和证据指针。条件/否定/核实请求与估算分别保留；一句中的提醒不会掩盖另一句确定断言。

SUPPORTED只表示某条与保存字段对应，不证明全文正确。UNSUPPORTED表示所给来源不足，不等于现实事实为假。CONTRADICTED只用于当前能精确核对的同日期预报字段/数值冲突；错日期本身判缺支持。未覆盖内容为NOT_ASSESSABLE；条件等非事实陈述为NOT_ASSERTION。未知文本不自动当成正确。

地点类别与名称不能证明具体展览；坐标不能证明路线耗时；历史宣传单不能证明当前价格；原始openingHours不能证明当天营业/免预约。这些来源能力边界比字面名称匹配更细，但底层仍是有限模式和结构化字段比较。复杂语义、引用、跨句指代、变体可能漏报或误报。

旧计划的quality不回填，PDF不改，原规则版本不升级。尚未将新断言报告纳入一次纠错的接受门槛；当前“纠错成功”不能被解释为解决新报告中的全部问题。

## 人工确认与对照

用户明确确认了上轮12例/36维AI预填标签。新增human-confirmation-v1.json绑定整个原AI标签hash、各case/subject/annotation hash与明确确认语句；原AI文件不变。身份依据为当前对话声明，1人、AI辅助、非独立盲评。原双人独立协议不放宽。

source_review_report.py校验用户确认、运行输入、每条UTF-16跨度与证据指针，再做已知案例诊断。来源二分类只看是否存在缺依据/矛盾提醒，纯预算warning从来源指标排除。分母与误报/漏报数量明确保存，不能把对已知12例的结果宣传为泛化准确率。

另冻结RAGTruth外部人工test小样本24例，保留来源、原标签、MIT许可、commit和hash。当前核验器不适配英文通用来源，24例全部弃权；外部覆盖0/24、准确率/precision/recall均null。只验证适用范围，尚未证明外部效果。

## 重跑

项目根目录、Java21+与Maven/Python可用时：

    python tools/evaluation/run_source_review.py

本机继续使用独立Maven配置：

    $env:JAVA_HOME='C:/Users/zybroot/.jdks/openjdk-22.0.1'
    $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
    python tools/evaluation/run_source_review.py --maven-settings ../../../tmp/yangbin-maven-settings.xml

该命令先校验冻结数据/标签确认，然后运行SourceClaimReviewTest与FrozenDatasetEvaluationTest，输出新runId、外部适用性记录、JSON/CSV/HTML对照，不覆盖旧结果。既有8故障场景一起回归，4真实任务仍为NOT_RUN。较大定向回归名单与证据见[验收](SOURCE-REVIEW-VALIDATION.md)。

原始公共文件不必进入Git；选取的24例与许可在evaluation/datasets/ragtruth-test-source24-v1。导入器离线接受指定上游目录，先校验固定文件hash，再按冻结顺序抽样。更换数据版本应新建目录，不覆盖本版。

## 遗留

- 真正支持自由文本来源的通用语义核验、英文外部效果评测。
- 未见中文新案例的独立评审与误报/漏报评估。
- 与一次纠错新门槛的设计、兼容性与独立回归。
- 4个真实任务适配、预算执行、Token/费用采集；本轮未发送真实上游请求。

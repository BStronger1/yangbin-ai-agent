# 冻结数据集离线执行与只读看板

第3步的离线部分：12个固定输出回放、8个故障场景适配、4个真实任务保留未运行。评测不需要应用凭据，不发送上游模型/地图/天气请求；真实任务执行器尚未实现。

## 运行

在项目根目录，Java 21+、Maven和Python可用时：

    python tools/evaluation/run_offline.py

本机Maven全局配置损坏，继续使用已存在的独立配置：

    $env:JAVA_HOME='C:/Users/zybroot/.jdks/openjdk-22.0.1'
    $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
    python tools/evaluation/run_offline.py --maven-settings ../../../tmp/yangbin-maven-settings.xml

结果保存在target/evaluation/<runId>/：observations.json为Java执行原始记录；各Fxx目录保存任务检查点、方案、纠错ledger及合成PDF；report目录包含JSON、CSV、index.html。每次生成新runId，不覆盖之前结果。只读HTML可本地打开，无服务/API依赖，支持类型筛选、搜索与逐例展开。

Java执行器在启动时核对冻结文件hash，报告绑定datasetHash、caseHash、inputHash、runId、开始/结束时间和实际源码文件hash，结束时核对源码未并发改变。HEAD仅是提交基线，不替代未提交源码指纹。Python再次校验冻结协议并保存resolvedInputHash、annotationHash、observationHash及报告渲染脚本hash。Token/费用未知保持null。

## 执行边界

固定回放分别调用真实validate/validateItinerary/validatePlaces和PlanQualityService.assess。结构失败也保存规则报告，不因无法发布而删除样本。结构拒绝与来源告警是不同维度，预算告警不计为来源支持标签冲突。

故障适配器使用真实TaskService、PlannerService、PlanRepairService、SkillService、JSON文件和PDF导出；只替代ChatModel、外部来源fetch与规划模型。普通输出固定取G01；纠错/版本/隔离场景取G12，在运行记录中保存实际替身文本及输入。

| 场景 | 已执行动作与边界 |
|---|---|
| F01 | 来源不可用→保存失败→恢复后重试；要求/知识步骤不重跑 |
| F02 | 首次PDF步骤注入异常→原方案保持→重试成功；不重调生成模型 |
| F03 | 模型步骤RUNNING持久检查点复制；替身返回后模拟Error导致结果未保存；服务重建后INTERRUPTED且不自动执行，手动重试记录第二次尝试。不是OS进程终止实验，也不保证上游只计费一次 |
| F04 | 两线程并发确认；计划与技能冻结，只有一个执行队列和方案 |
| F05 | 已有v2时请求按v1纠错；版本冲突且无新纠错模型请求 |
| F06 | 纠错候选原样返回；REJECTED不发布，重复请求复用一次消耗记录 |
| F07 | A的任务/方案/PDF/纠错确实存在，再以B读取；服务/控制器拒绝且不返回私有正文。未覆盖HTTP登录过滤器 |
| F08 | 等待确认时取消→重新加载→拒绝确认；计划之后工具不执行 |

故障失败写为HARNESS_FAILED并保留已完成断言/异常/产物，不从分母移除。JUnit失败使命令失败；CI用always归档已有原始记录。应用旧数据、线上任务与旧纠错次数不参与本评测。

## 标签与解释

[辅助标签和公共人工数据](EVALUATION-EXTERNAL-LABELS.md)。本轮填写36维AI辅助标签，人工为0。只列出规则与辅助判断的差异，不计算语义准确率、误报率、漏报率或模型成功率。

合成快照日期2026-11-14晚于本轮运行日，所有固定案例产生SNAPSHOT_TIME_INVALID信息项，不能当作语义warning。源码规则使用系统时钟，时间类信息会随日期变化；应比较相同输入、规则版本和时间条件，不承诺所有时间字段逐字节重现。checkedAt/耗时/runId本来就变化。

CI新增执行器和报告归档配置，只有提交后实际CI运行才能声称远程验证。本轮不修改业务规则、不部署、不运行4个真实任务。

# 总设置中的模型与推理强度

M028 在已有账号私有模型能力上扩展服务连接与启用列表。旧单模型配置和历史 profileId 保留，旧配置管理入口也移至总设置。

## 使用

1. 侧栏「总设置」进入 `/settings`，添加服务商、API 地址和 API Key。每个连接只保存一份加密密钥；编辑时留空保留，不回显。
2. 点击「获取模型」，读取供应商对该密钥返回的模型列表。获取列表不会发送生成任务。服务不提供列表时，可复用连接手动填模型 ID。
3. 勾选希望在输入区显示的模型并保存。建议约 6 个，无 6 个硬上限；勾选两个且取消系统默认后，输入区只有这两个。新发现模型不会自动启用。
4. 对话中选模型及其支持的推理强度。各空间、各模型分别记忆选择。高强度可能增加耗时和用量，不保证更准确。

取消勾选阻止新调用，不取消后台任务、不删除历史。已失效的选择要求重新选择，不暗中切到系统模型。设置变更不会改写历史模型/强度快照。空选择不能保存；删除最后一个有效连接后需重新选择模型。

## 协议和能力

|服务|实际请求参数|能力来源|
|---|---|---|
|OpenAI|`reasoning_effort`，推理模型保留 `max_completion_tokens` 输出上限|已核实型号与日期版本白名单|
|DeepSeek|`thinking.type` 与 `reasoning_effort`；关闭思考时省略 effort|模型列表 `effort.supported_levels`，已知型号后备|
|Anthropic|`output_config.effort`，不擅自增加 thinking 预算|模型列表 `capabilities.effort`，已知型号后备|
|Kimi / 未知兼容服务或型号|服务默认|尚未核实可调参数，不显示虚假档位|

供应商列出的模型可能包含图片、音频或 embedding 模型；列表可见不等于支持本项目的 Chat/工具接口。已知在当前 Chat Completions 接口不支持工具调用的 GPT-6 Astra / GPT-6.1 Sol 会拒绝 Coding。未接入 Responses API，不把上游全部型号能力当作已支持。

列表最多 1000 个模型、10 页、每页 2 MiB，HTTP 总时限 30 秒（系统 DNS 解析沿用基础实现）；超限或分页失败整体报错，不以部分列表冒充全部。换密钥后需重新获取列表。新列表缺失的模型禁用但保留历史信息。

## 安全与测试边界

API Key 只经加密存储与服务端传输，前端不存到 localStorage/sessionStorage。发现接口复用端点/DNS检查，禁止重定向和自动重试，供应商错误体不回传；发现网络不占用账号存储全局锁。普通账号不得访问私网端点。连接与启用列表按账号隔离，模式切换不能绕过禁用、预算或工具授权。

Java 和 Coding 均传递真实参数；Coding 在最终请求体写入参数后才进行预算准入。离线测试验证最终 HTTP 请求、预算上限、历史/幂等性、工具回合、跨账号拒绝与浏览器流程；不代表供应商真实模型质量或承载能力，未重放历史付费测试。

官方接口依据（2026-10-22核对）：[OpenAI reasoning](https://developers.openai.com/api/docs/guides/reasoning?api-mode=chat)、[Chat Completions 参数](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[OpenAI models](https://developers.openai.com/api/reference/resources/models/methods/list)、[DeepSeek models](https://api-docs.deepseek.com/api/list-models/)、[DeepSeek thinking](https://api-docs.deepseek.com/guides/thinking_mode/)、[Anthropic models](https://platform.claude.com/docs/en/api/models/list)、[Anthropic effort](https://platform.claude.com/docs/en/build-with-claude/effort)。

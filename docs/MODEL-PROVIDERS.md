# 自带模型（BYOK）

在对话或工作输入栏打开模型设置。创建一个配置，填名称、厂商、Base URL、模型 ID、API Key，保存后选择使用。可以创建多组配置；对话和工作分别记住选择。运行中的轮次固定使用提交时的模型，不随下拉框变化。

## 地址规则

| 厂商 | 协议 | Base URL示例 |
| --- | --- | --- |
| OpenAI | Chat Completions | `https://api.openai.com/v1` |
| Claude / Anthropic | Messages | `https://api.anthropic.com` |
| DeepSeek | Chat Completions | `https://api.deepseek.com` |
| Kimi | Chat Completions | `https://api.moonshot.ai/v1` |
| 自定义兼容 | Chat Completions | 服务商提供的完整基础地址 |

填写基础地址，不填完整的 `/chat/completions` 或 `/messages` 路由。不会给任意自定义地址盲目增加 `/v1`。Kimi国内账号可按其服务商说明使用对应区域地址。模型ID可手填，预设仅是示例，是否可用取决于账号权限。

本轮核验来源：[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[Claude API](https://platform.claude.com/docs/en/api/overview)、[DeepSeek接入](https://api-docs.deepseek.com/)、[Kimi接入](https://platform.kimi.ai/docs/overview)。文档会变化，服务商的新模型参数可能需要额外适配。

## 密钥与账号

API Key仅提交给当前部署后端，持久存储使用AES-256-GCM；GET接口只返回hasKey，不返回明文。编辑配置时留空保留原密钥，填写新值替换。删除配置会撤销后续任务对该配置的使用；已开始的任务需单独取消。

浏览器不把API Key放入URL或localStorage/sessionStorage。任务记录只保存模型配置ID和非密钥快照。模型请求由服务器发出，因此运行服务的机器必须能访问供应商；浏览器能访问不代表服务器能访问。

普通账号只能使用HTTPS公网模型端点。管理员可以设置受信内部端点，用于本地模型或协议测试；不要把管理员权限发给不可信用户。重定向被禁用，避免凭据发往意外地址。

## 备份

默认模型存储为 `tmp/model-profiles`，包括每账号加密配置与 `master.key`。必须一起备份、限制访问；不能丢掉主密钥后只恢复密文。也可通过 `MODEL_PROFILE_MASTER_KEY` 提供base64编码的32字节主密钥，需由部署者安全保管。加密不保护已经控制服务账号的攻击者。

## 排查

- **401/403**：核对该地址对应的API Key和模型权限，OpenAI订阅或Claude聊天订阅不等于API额度。
- **404**：核对Base URL和模型ID，避免多写或漏写 `/v1`。
- **工具调用失败**：确认模型支持该协议的工具调用；不同厂商的推理字段可能有兼容差异。
- **Coding运行时未就绪**：检查Node版本、`npm ci --ignore-scripts --prefix coding-agent`和 `node coding-agent/src/main.mjs --check`。
- **配置删除/账号切换**：重新选择可用配置。旧任务历史仍保留，不能用另一个账号的配置继续。

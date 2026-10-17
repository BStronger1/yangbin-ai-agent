# yangbin AI Agent

**一个网页，两种工作方式：AI 超级智能体对话与基于 pi 的 Coding Agent。**

Bring your own model, keep projects private, and review tool actions before they run.

[![Build](https://github.com/BStronger1/yangbin-ai-agent/actions/workflows/build.yml/badge.svg)](https://github.com/BStronger1/yangbin-ai-agent/actions/workflows/build.yml)

[快速开始](#本地运行) · [模型接入](docs/MODEL-PROVIDERS.md) · [部署](docs/SELF-HOSTING.md) · [贡献](CONTRIBUTING.md) · [来源与许可](NOTICE.md)

## 能做什么

- **对话**：选择自己的模型，使用现有 ReAct 超级智能体与工具链。
- **工作**：创建服务端私有项目，让 pi Coding Agent 读取、编写、编辑文件；查看实际工具事件、审批、取消、历史任务和最近一轮文本差异。
- **自带模型**：Claude 原生 Messages、OpenAI / DeepSeek / Kimi 的兼容接口，以及自定义兼容 Base URL 和模型 ID。配置按账号隔离，API Key 在服务器加密保存、不回显。
- **可控执行**：始终询问 / 按需确认 / 全部允许；命令工具还需管理员在服务器单独开启。
- **原工作台**：技能、伙伴、知识库、出行规划与来源核查仍保留。NarrativePPT 是需要独立配置的可选服务。

![账号私有模型设置](docs/images/model-settings.png)

*界面截图使用模拟账号与示例地址，不包含真实凭据。*

## 本地运行

需要 **Java 21+、Maven 3.9+、Node >=22.19、Python >=3.10**。首次构建需要下载依赖。

```bash
git clone https://github.com/BStronger1/yangbin-ai-agent.git
cd yangbin-ai-agent
python -m venv .venv
# Linux/macOS: source .venv/bin/activate
# Windows PowerShell: .venv\Scripts\Activate.ps1
python -m pip install -r deploy/turing/requirements.txt
python tools/run_local.py --build
```

首次启动会让你设置本地管理员密码（输入不显示）。打开 **http://127.0.0.1:18825/work**，默认账号为 `admin`。在输入栏的模型菜单进入“模型设置”，填写厂商、Base URL、模型 ID 和 API Key，然后分别在“对话”和“工作”中选择它。

无需先配置全站共享模型。已有构建后直接 `python tools/run_local.py`；用 `--check` 检查环境，用 Ctrl+C 停止本次服务。启动器仅监听本机，不覆盖已有账号密码。MCP、RAG、NarrativePPT 和命令执行默认不开启。

## 一个可验证的开始

切到“工作” → 新建项目 → 选择模型 → 输入：

> 创建一个 index.html，实现一个带增减按钮的计数器。先说明计划，在获得写入批准后创建文件。

检查审批中的路径与内容，再允许执行。在“项目文件”中查看实际文件和差异。模型说“完成”不替代运行测试；命令功能关闭时，页面不应将未执行的命令写成成功。

## 当前边界

当前文件位于服务器的账号私有项目中，**不是你浏览器所在电脑的仓库**。尚无本机连接器、Git/PR工作流、应用预览或产品内多Agent并行调度。对话模式沿用单次Manus任务；Coding任务支持历史恢复。

命令工具以服务账号执行，**不是操作系统沙箱**。请勿把开启命令的部署用于不可信多租户。文件工具支持有界UTF-8文本；最近一轮差异不等于完整Git diff。厂商/模型可用性和工具调用支持取决于你的账号及所选模型，协议模拟测试不等于每家付费模型实测。

## 结构

| 路径 | 职责 |
| --- | --- |
| `yangbin-ai-agent-frontend/` | Vue 3 界面、双模式输入栏与模型设置 |
| `src/main/java/` | Spring Boot 账号上下文、模型配置、任务和工具编排 |
| `coding-agent/` | 固定版本 pi SDK、JSONL进程接口与审批工具 |
| `deploy/turing/` | 会话/CSRF网关、账号库及专用部署脚本 |
| `tools/run_local.py` | 不依赖个人服务器目录的本地启动 |

## 验证与贡献

CI使用本地模拟模型，避免消耗贡献者API额度。协议、隔离、审批和页面测试说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。错误报告请说明操作步骤、预期/实际结果、浏览器与模型协议，**不要附API Key或私人会话**。

欢迎提交可复现问题、文档纠错、协议兼容性修复和可验证的小PR。新功能与能力边界见 [ROADMAP.md](ROADMAP.md)；不以测试数或单个案例宣称通用模型质量。

## 来源与许可

本项目是 [liyupi/yu-ai-agent](https://github.com/liyupi/yu-ai-agent) 的课程学习改造，个人扩展包括账号工作台、验证链路、pi适配和BYOK体验。pi SDK的版权许可保留在 [coding-agent/PI-LICENSE](coding-agent/PI-LICENSE)。原始快照未附整体许可证，当前不声称整个仓库获得MIT/Apache等开放授权；请先阅读 [NOTICE.md](NOTICE.md) 与 [LICENSING.md](LICENSING.md)。

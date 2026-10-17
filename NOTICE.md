# 来源说明

本项目由 BStronger1（yangbin）基于程序员鱼皮的教学项目进行学习改编、配置修复和部署。

- 原始仓库：https://github.com/liyupi/yu-ai-agent
- 导入基线：6d4608c（完整提交见原仓库历史）
- 原项目介绍和作者信息：[原始 README](https://github.com/liyupi/yu-ai-agent/blob/6d4608c/README.md)
- 当前源快照未附独立 LICENSE 文件；本说明不新增或扩大原项目及其依赖的授权。
- GitHub Contributors 反映此发布仓库的提交作者；不表示导入源码全部为维护者原创。

原始 Git 历史保留在本地 master 分支及 upstream 远程。公开仓库 main 分支以明确标注来源的独立导入提交开始，不改写原作者的提交身份。

## Coding Agent 的 pi 集成（2026-10-18）

`coding-agent/` 使用固定版本 `@earendil-works/pi-coding-agent` 1.3.0。pi 提供模型工具循环、会话及SDK，项目新增网页入口、Java账号与任务管理、JSONL适配、审批和受控文件工具。上游版权/许可证保留在 [PI-LICENSE](coding-agent/PI-LICENSE)，不将SDK能力描述为从零原创。

设计时阅读用户提供的本地 `codingAgent/pi` 与 `codingAgent/pi-gui` 参考目录；当前实现不复制整套 pi-gui 桌面应用，也不提供其全部能力。

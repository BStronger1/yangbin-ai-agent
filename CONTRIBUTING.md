# 贡献指南

先阅读NOTICE.md与LICENSING.md。仓库保留课程来源，提交贡献不代表重新授权全部上游源码。

1. 复现问题，说明版本、输入、预期与实际结果；移除凭据和私人数据。
2. 使用独立分支，小范围修改。保持账号隔离、审批、取消、幂等与历史兼容。
3. 提供能证明行为的测试，优先loopback模拟模型，不要求CI读取真实API Key。
4. 提交PR，说明用户行为变化、验证结果及明确限制。不要提交tmp、日志、配置、构建产物或真实对话。

## 本地验证

```bash
npm ci --ignore-scripts --prefix coding-agent
npm test --prefix coding-agent
python -m pip install -r deploy/turing/requirements.txt
python -m unittest discover -s deploy/turing -p 'test_*.py'
npm ci --prefix yangbin-ai-agent-frontend
npm run build --prefix yangbin-ai-agent-frontend
```

浏览器安装与本地运行命令见 [前端测试说明](yangbin-ai-agent-frontend/tests/README.md)。Node 与 npm 应来自同一套受支持的安装。

Java定向回归及完整CI集合以 `.github/workflows/build.yml` 为准。浏览器测试运行真实生产构建与API协议夹具，不是供应商模型质量评测。测试失败时保留复现与错误，不把跳过测试写成通过。

推荐从模型兼容性、有证据的界面问题、安装说明与可访问性入手。请勿批量发布宣传issue、自动邀请Star或制造虚假社区互动。安全问题按SECURITY.md处理。

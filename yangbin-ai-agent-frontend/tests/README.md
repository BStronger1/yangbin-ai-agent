# 模型配置浏览器检查

使用 Node.js 22.19+，在前端目录运行以下跨平台命令：

```sh
npm ci
npx playwright install chromium
npm run test:models-ui
```

Linux CI 可使用 `npx playwright install --with-deps chromium` 安装浏览器及系统依赖。若已安装 Chrome/Chromium，可将 `CHROME_EXECUTABLE_PATH` 设置为其可执行文件路径，跳过浏览器下载。

脚本先构建前端，再使用本地 API 夹具检查模型管理、密钥输入、双模式选择、待确认重试和手机布局；不调用真实模型。默认报告与截图写入前端项目的 `tmp/model-profiles-ui/`。可用 `EVIDENCE_DIR` 指定其他输出目录。两个环境变量均为可选项，测试代码不依赖个人机器路径。

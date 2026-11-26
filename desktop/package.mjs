import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url)),repo=path.dirname(root);
const metadata=JSON.parse(await fs.readFile(path.join(root,'package.json'),'utf8'));
const out=path.resolve(process.env.YANGBIN_PACKAGE_DIR || path.join(root,'out',`yangbin-ai-agent-${metadata.version}-win32-x64`));
try{await fs.access(out);throw new Error('Output already exists; preserve it and choose a new release directory');}catch(e){if(e.code!=='ENOENT')throw e;}
await fs.cp(path.join(root,'node_modules/electron/dist'),out,{recursive:true});
const target=path.join(out,'resources/app');await fs.mkdir(path.join(target,'desktop'),{recursive:true});
for(const file of ['main.mjs','preload.cjs','native-directory.mjs','candidate-workspace.mjs'])await fs.copyFile(path.join(root,file),path.join(target,'desktop',file));
await fs.mkdir(path.join(target,'coding-agent/src'),{recursive:true});
const runnerFiles=JSON.parse(await fs.readFile(path.join(repo,'coding-agent/local-runner-files.json'),'utf8'));
for(const file of runnerFiles)await fs.copyFile(path.join(repo,'coding-agent/src',file),path.join(target,'coding-agent/src',file));
await fs.mkdir(path.join(target,'yangbin-ai-agent-frontend/src'),{recursive:true});
await fs.copyFile(path.join(repo,'yangbin-ai-agent-frontend/src/local-directory.js'),path.join(target,'yangbin-ai-agent-frontend/src/local-directory.js'));
await fs.writeFile(path.join(target,'package.json'),JSON.stringify({name:'yangbin-ai-agent',productName:'yangbin AI Agent',version:metadata.version,type:'module',main:'desktop/main.mjs'}));
await fs.rename(path.join(out,'electron.exe'),path.join(out,'yangbin AI Agent.exe'));
await fs.writeFile(path.join(out,'使用说明.txt'),'双击 yangbin AI Agent.exe，登录现有账号。\r\n在侧栏打开「我的博客」写 Markdown。点击「保存草稿」后，可以登录同一账号在手机上继续；点击「公开发布」后可复制免登录阅读链接，或将文章导入知识库。\r\n在 Coding Agent 中选择本机项目。当前任务在独立候选中修改文件，原目录保持不变。审阅后下载补丁自行合并；候选模式暂不启动本机程序。\r\n退出客户端会停止由客户端启动的程序。网页及模型服务需要联网。\r\n更新时从应用菜单打开下载页，结束任务并退出旧客户端后，解压新版再启动。原账号会话与项目绑定保留。此版本尚未配置 Windows 代码签名。\r\n');
console.log(JSON.stringify({directory:out,executable:path.join(out,'yangbin AI Agent.exe')}));

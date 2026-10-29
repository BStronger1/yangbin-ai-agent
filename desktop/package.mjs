import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url)),repo=path.dirname(root);
const out=path.join(root,'out','yangbin-ai-agent-win32-x64');
try{await fs.access(out);throw new Error('Output already exists; preserve it and choose a new release directory');}catch(e){if(e.code!=='ENOENT')throw e;}
await fs.cp(path.join(root,'node_modules/electron/dist'),out,{recursive:true});
const target=path.join(out,'resources/app');await fs.mkdir(path.join(target,'desktop'),{recursive:true});
for(const file of ['main.mjs','preload.cjs','native-directory.mjs'])await fs.copyFile(path.join(root,file),path.join(target,'desktop',file));
await fs.mkdir(path.join(target,'coding-agent/src'),{recursive:true});
for(const file of ['tools.mjs','local-runner.mjs','sandbox.mjs','context-store.mjs'])await fs.copyFile(path.join(repo,'coding-agent/src',file),path.join(target,'coding-agent/src',file));
await fs.mkdir(path.join(target,'yangbin-ai-agent-frontend/src'),{recursive:true});
await fs.copyFile(path.join(repo,'yangbin-ai-agent-frontend/src/local-directory.js'),path.join(target,'yangbin-ai-agent-frontend/src/local-directory.js'));
await fs.writeFile(path.join(target,'package.json'),JSON.stringify({name:'yangbin-ai-agent',productName:'yangbin AI Agent',version:'0.1.0',type:'module',main:'desktop/main.mjs'}));
await fs.rename(path.join(out,'electron.exe'),path.join(out,'yangbin AI Agent.exe'));
await fs.writeFile(path.join(out,'使用说明.txt'),'双击 yangbin AI Agent.exe，登录现有账号，在 Coding Agent 中选择本机项目。\r\n无需安装 Node.js、单独运行器或配对。代码直接写回所选目录；启动程序前会请求确认。\r\n退出客户端会停止由客户端启动的程序。网页及模型服务需要联网。\r\n此版本尚未配置 Windows 代码签名。\r\n');
console.log(JSON.stringify({directory:out,executable:path.join(out,'yangbin AI Agent.exe')}));

"""Build the credential-free, dependency-free local companion download."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
root = Path(__file__).resolve().parents[2]
destination = root / 'yangbin-ai-agent-frontend/public/downloads/yangbin-local-runner.zip'
destination.parent.mkdir(parents=True, exist_ok=True)
launcher = '''import {createInterface} from 'node:readline/promises';
import {createLocalRunner} from './src/local-runner.mjs';
if(Number(process.versions.node.split('.')[0])<22)throw new Error('请先安装 Node.js 22 或更新版本，再启动运行器。');
const input=createInterface({input:process.stdin,output:process.stdout});
const workspace=(process.argv[2] || await input.question('请输入项目文件夹完整路径：')).trim().replace(/^"|"$/g,'');input.close();
const runner=await createLocalRunner({workspace,origin:'https://www.yangbinai.homes'});
console.log('项目：'+runner.manager.workspace+'\\n请保持此窗口打开。打开以下链接，在网页中选择同一项目目录：');
console.log('https://www.yangbinai.homes/coding#local-runner='+encodeURIComponent(JSON.stringify({url:runner.url,token:runner.token})));
console.log('也可手动填写地址：'+runner.url+'\\n连接码：'+runner.token);
for(const signal of ['SIGINT','SIGTERM'])process.once(signal,async()=>{await runner.close();process.exit(0);});
'''
with ZipFile(destination, 'w', ZIP_DEFLATED) as bundle:
    for name in ('local-runner.mjs', 'tools.mjs', 'sandbox.mjs', 'context-store.mjs'):
        bundle.write(root / 'coding-agent/src' / name, 'src/' + name)
    bundle.writestr('start.mjs', launcher)
    bundle.writestr('start.cmd', '@echo off\r\ncd /d "%~dp0"\r\nnode start.mjs\r\npause\r\n')
    bundle.writestr('README.txt', '本机运行器\n需要 Node.js 22 或更新版本。解压后双击 start.cmd（Windows），或运行 node start.mjs。\n输入项目目录，打开输出的连接链接，选择同一目录。浏览器询问本地网络访问时允许。\n命令以你的本机账号运行，不是隔离环境；网页每次启动命令前显示确认。关闭运行器前可在网页停止项目进程。\n此包不包含模型、密钥或用户文件；模型仍使用网站里选择的连接。\n')
print(destination)

"""Generate the README technology overview (standard library only).

Run from any directory: python tools/generate_architecture.py
"""
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/images/architecture-overview.svg'
parts = ['''<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="1380" viewBox="0 0 1440 1380" role="img" aria-labelledby="title desc">
<title id="title">yangbin AI Agent 技术架构全景</title>
<desc id="desc">Vue 前端、Spring Boot 与 Spring AI 后端、Python 账号网关、pi Coding 运行时，以及可选 RAG、MCP 和 NarrativePPT 扩展。右侧为开发、测试与交付工具。</desc>
<style>text{font-family:"Microsoft YaHei","Noto Sans CJK SC","PingFang SC",Arial,sans-serif} .card{fill:#fff;font-size:19px;font-weight:600} .section{font-size:27px;font-weight:700} .group{font-size:19px;font-weight:700}</style>
<rect width="1440" height="1380" fill="#fff"/>
''']

def rect(x, y, w, h, fill, stroke='none', radius=14, dash=False, sw=2):
    parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"'+(' stroke-dasharray="7 6"' if dash else '')+'/>')

def text(x, y, label, color='#334155', size=19, weight=400, anchor='middle', cls=''):
    parts.append(f'<text x="{x}" y="{y}" text-anchor="{anchor}" fill="{color}" font-size="{size}" font-weight="{weight}"'+(f' class="{cls}"' if cls else '')+f'>{escape(label)}</text>')

def panel(x, y, w, h, title, fill, stroke):
    rect(x,y,w,h,fill,stroke,32,sw=3)
    text(x+w/2,y+39,title,stroke,cls='section')

def group(x,y,w,h,title,color):
    rect(x,y,w,h,'#ffffff',color,18,True)
    text(x+w/2,y+27,title,'#526274',cls='group')

def card(x,y,w,h,lines,color):
    rect(x,y,w,h,color,radius=10)
    for i,line in enumerate(lines):
        text(x+w/2,y+h/2+7+(i-(len(lines)-1)/2)*25,line,cls='card')

blue='#1784bd';green='#43a650';purple='#8245ae';teal='#008f88';orange='#f49a09';pink='#db256a';slate='#607e8b';indigo='#4954b3'
text(28,47,'yangbin AI Agent', '#14283e',34,700,'start')
text(28,78,'技术架构全景 · 对话超级智能体 + pi Coding Agent','#64748b',20,400,'start')
text(1412,48,'当前实现 · 2026.10','#64748b',18,400,'end')
panel(24,100,1108,255,'前端技术栈','#edf8ff',blue)
group(44,155,1068,87,'界面与内容展示','#7cbca2')
for x,w,lines,c in [(62,228,['Vue 3 · 组件化界面'],green),(308,228,['Vue Router · 路由'],blue),(554,228,['Axios · API 请求'],slate),(800,294,['Marked + DOMPurify'],purple)]:
    card(x,190,w,39,lines,c)
group(44,253,1068,83,'工程化与实时交互','#92acd1')
for x,w,lines,c in [(62,228,['Vite 8 · 构建'],orange),(308,228,['JavaScript / CSS'],indigo),(554,228,['SSE · 对话事件'],blue),(800,294,['HTTP 轮询 · 工作状态'],teal)]:
    card(x,287,w,38,lines,c)

panel(24,375,1108,430,'后端技术栈','#fff6e7',orange)
group(44,431,524,166,'Java 业务核心','#82b985')
card(60,471,238,48,['Spring Boot 3 + Java 21'],green)
card(314,471,238,48,['Spring AI 1.0'],blue)
card(60,533,238,48,['YangbinManus · ReAct'],pink)
card(314,533,238,48,['Tool Calling'],pink)
group(588,431,524,166,'多厂商接入 · 自带 API Key','#dda0ba')
card(604,471,238,48,['Claude · Messages'],purple)
card(858,471,238,48,['OpenAI · 兼容接口'],blue)
card(604,533,238,48,['DeepSeek / Kimi'],orange)
card(858,533,238,48,['自定义 Base URL / 模型'],teal)
group(44,613,524,170,'账号网关与访问控制','#bea590')
card(60,653,238,50,['Python + aiohttp'],slate)
card(314,653,238,50,['登录会话 / CSRF'],indigo)
card(60,717,238,50,['静态资源 / 反向代理'],blue)
card(314,717,238,50,['内部鉴权 / 账号隔离'],teal)
group(588,613,524,170,'数据持久化与文件产物','#baa4cc')
card(604,653,238,50,['SQLite · 账号 / 会话'],orange)
card(858,653,238,50,['JSON / JSONL / 文件'],purple)
card(604,717,238,50,['AES-256-GCM · 密钥'],slate)
card(858,717,238,50,['Apache POI / iText'],green)

panel(24,825,1108,260,'Coding Agent · 工作执行层','#eefbf7',teal)
group(44,881,1068,182,'Java 管理任务与审批 · 独立 Node 子进程执行编码循环','#88bdae')
card(62,921,246,55,['Node.js ≥ 22.19'],green)
card(324,921,246,55,['pi SDK 1.3.0'],blue)
card(586,921,246,55,['stdin / stdout JSONL'],slate)
card(848,921,246,55,['账号私有项目目录'],purple)
card(62,991,246,55,['文件读取 / 编写 / 编辑'],teal)
card(324,991,246,55,['三档工具审批'],orange)
card(586,991,246,55,['取消 / 历史恢复'],indigo)
card(848,991,246,55,['模型快照 / 文本差异'],pink)

panel(24,1105,1108,200,'可选扩展 · 需额外配置或部署','#faf6ff',purple)
card(46,1164,342,68,['RAG · SimpleVectorStore','FastEmbed / 多语 MiniLM'],green)
card(407,1164,342,68,['MCP 服务','工具注册 / 查看 / 测试'],teal)
card(768,1164,342,68,['NarrativePPT','独立演示文稿服务'],purple)
text(578,1273,'扩展按模块启用；Coding Agent 尚未自动接入 RAG / MCP / Skills。','#725a83',19)

panel(1154,100,262,1205,'开发与交付','#f5edf9',purple)
group(1174,163,222,247,'构建与协作','#ccafd8')
card(1190,206,190,76,['Maven','Java 构建'],orange)
card(1190,299,190,76,['npm / Git / GitHub','依赖与版本协作'],indigo)
group(1174,432,222,420,'自动化验证','#b2acd8')
card(1190,478,190,74,['JUnit','Java 测试'],green)
card(1190,565,190,74,['Node Test Runner','运行时测试'],teal)
card(1190,652,190,74,['Python unittest','网关与账号测试'],slate)
card(1190,739,190,74,['Playwright','浏览器交互验收'],blue)
group(1174,876,222,308,'持续集成与启动','#cfb1c5')
card(1190,922,190,88,['GitHub Actions','构建与回归检查'],purple)
card(1190,1034,190,88,['本地启动器','tools/run_local.py'],pink)
text(1285,1231,'单实例部署基线','#745888',19,600)
text(1285,1265,'可选服务独立配置','#745888',18)
text(26,1347,'课程项目改造与个人扩展 · pi 为复用的上游运行时；工具审批不等于操作系统沙箱。','#64748b',18,400,'start')
parts.append('</svg>\n')
OUT.write_text(''.join(parts),encoding='utf-8')
print(OUT)

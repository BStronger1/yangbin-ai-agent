"""Public, fixed-manifest desktop downloads, outside replaceable frontend builds."""
import json
import re
from pathlib import Path
from aiohttp import web


def release(root):
    directory = root / 'downloads'
    manifest = directory / 'desktop.json'
    if directory.is_symlink() or manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > 8192:
        raise web.HTTPServiceUnavailable(text='客户端暂不可下载，请稍后重试')
    try:
        data = json.loads(manifest.read_text(encoding='utf-8'))
        version = data['version']
        if not re.fullmatch(r'\d+\.\d+\.\d+', version): raise ValueError()
        filename = f'yangbin-ai-agent-windows-x64-{version}.zip'
        if data['filename'] != filename or not re.fullmatch(r'[a-f0-9]{64}', data['sha256']): raise ValueError()
        item = directory / filename
        if type(data['bytes']) is not int or data['bytes'] <= 0 or item.is_symlink() or not item.is_file() or item.stat().st_size != data['bytes']: raise ValueError()
        return {k:data[k] for k in ('version','filename','bytes','sha256')}, item
    except (ValueError, KeyError, TypeError, OSError):
        raise web.HTTPServiceUnavailable(text='客户端发布信息不可用')


def register_downloads(app, root):
    root = Path(root)
    async def metadata(request):
        data, _ = release(root)
        return web.json_response(data, headers={'Cache-Control':'no-store'})
    async def download(request):
        data, item = release(root)
        if request.match_info['filename'] != data['filename']: raise web.HTTPNotFound()
        return web.FileResponse(item, headers={
            'Content-Type':'application/zip', 'Content-Disposition':f'attachment; filename="{data["filename"]}"',
            'Cache-Control':'public, max-age=31536000, immutable', 'X-Content-Type-Options':'nosniff'})
    app.router.add_get('/downloads/desktop.json', metadata)
    app.router.add_get('/downloads/{filename}', download)

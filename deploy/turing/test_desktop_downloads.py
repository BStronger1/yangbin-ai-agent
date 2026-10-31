import hashlib,json,tempfile,unittest
from pathlib import Path
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from desktop_downloads import register_downloads

class DownloadsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.folder=self.root/'downloads';self.folder.mkdir()
        self.filename='yangbin-ai-agent-windows-x64-0.2.1.zip';self.body=b'PK-test-download-bytes'
        (self.folder/self.filename).write_bytes(self.body)
        self.manifest={'version':'0.2.1','filename':self.filename,'bytes':len(self.body),'sha256':hashlib.sha256(self.body).hexdigest()}
        self.save();app=web.Application();register_downloads(app,self.root)
        self.client=TestClient(TestServer(app));await self.client.start_server()
    def save(self): (self.folder/'desktop.json').write_text(json.dumps(self.manifest))
    async def asyncTearDown(self): await self.client.close();self.tmp.cleanup()
    async def test_public_metadata_and_exact_download(self):
        r=await self.client.get('/downloads/desktop.json');self.assertEqual(r.status,200);self.assertEqual(await r.json(),self.manifest);self.assertEqual(r.headers['Cache-Control'],'no-store')
        r=await self.client.get('/downloads/'+self.filename);self.assertEqual(r.status,200);self.assertEqual(await r.read(),self.body);self.assertIn('attachment',r.headers['Content-Disposition']);self.assertIn('immutable',r.headers['Cache-Control'])
    async def test_range_and_head_support_resume_without_loading_whole_file(self):
        r=await self.client.get('/downloads/'+self.filename,headers={'Range':'bytes=0-3'});self.assertEqual(r.status,206);self.assertEqual(await r.read(),self.body[:4])
        r=await self.client.head('/downloads/'+self.filename);self.assertEqual(r.status,200);self.assertEqual(int(r.headers['Content-Length']),len(self.body));self.assertEqual(await r.read(),b'')
    async def test_only_published_filename_is_served(self):
        (self.folder/'private.txt').write_text('not public')
        for name in ('private.txt','other.zip','%2e%2e%2fprivate.txt'):
            r=await self.client.get('/downloads/'+name);self.assertEqual(r.status,404)
    async def test_missing_or_corrupt_release_is_unavailable_not_fake_success(self):
        (self.folder/'desktop.json').write_text('{bad');r=await self.client.get('/downloads/desktop.json');self.assertEqual(r.status,503)
        self.save();(self.folder/self.filename).unlink();r=await self.client.get('/downloads/'+self.filename);self.assertEqual(r.status,503)
    async def test_invalid_metadata_or_size_cannot_advertise_download(self):
        for field,value in [('filename','../private.zip'),('sha256','wrong'),('bytes',1),('version','../x')]:
            original=self.manifest[field];self.manifest[field]=value;self.save()
            r=await self.client.get('/downloads/desktop.json');self.assertEqual(r.status,503)
            self.manifest[field]=original

if __name__=='__main__':unittest.main()

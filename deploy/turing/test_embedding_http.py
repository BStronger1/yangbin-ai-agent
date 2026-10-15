"""HTTP framing regression tests, with a fake embedder and no model downloads."""
import http.client,importlib.util,json,os,threading,types,unittest
from pathlib import Path
from unittest.mock import patch
class Vector:
    def tolist(self):return [1.0,0.0,0.0]
class Embedder:
    def __init__(self,**kwargs):pass
    def embed(self,texts):return [Vector() for _ in texts]
class EmbeddingHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        module=types.ModuleType("fastembed");module.TextEmbedding=Embedder
        spec=importlib.util.spec_from_file_location("embedding_under_test",Path(__file__).with_name("embedding_server.py"))
        cls.app=importlib.util.module_from_spec(spec)
        with patch.dict("sys.modules",{"fastembed":module}),patch.dict(os.environ,{"EMBEDDING_CACHE":"unused"}):spec.loader.exec_module(cls.app)
        cls.server=cls.app.ThreadingHTTPServer(("127.0.0.1",0),cls.app.Handler)
        cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True);cls.thread.start()
    @classmethod
    def tearDownClass(cls):cls.server.shutdown();cls.server.server_close();cls.thread.join()
    def request(self,body,headers=None,chunked=False):
        conn=http.client.HTTPConnection("127.0.0.1",self.server.server_port,timeout=3)
        conn.request("POST","/v1/embeddings",body=body,headers=headers or {"Content-Type":"application/json"},encode_chunked=chunked)
        response=conn.getresponse();data=json.loads(response.read());status=response.status;conn.close();return status,data
    def payload(self):return json.dumps({"model":self.app.MODEL,"input":["沟通","婚姻"]}).encode()
    def test_content_length(self):
        status,data=self.request(self.payload());self.assertEqual(status,200);self.assertEqual(len(data["data"]),2)
    def test_chunked_java_compatible_request(self):
        body=self.payload();status,data=self.request(iter([body[:12],body[12:]]),chunked=True)
        self.assertEqual(status,200);self.assertEqual(data["data"][0]["embedding"],[1.0,0.0,0.0])
    def test_size_limit(self):
        status,_=self.request(b"",headers={"Content-Length":str(1024*1024+1)});self.assertEqual(status,413)
    def test_invalid_input(self):
        status,_=self.request(json.dumps({"model":self.app.MODEL,"input":[]}).encode());self.assertEqual(status,400)
if __name__=="__main__":unittest.main()

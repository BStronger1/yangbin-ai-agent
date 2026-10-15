"""Loopback-only OpenAI-compatible embeddings using an existing offline multilingual model."""
import json,os,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
os.environ.setdefault("HF_HUB_OFFLINE","1")
from fastembed import TextEmbedding
MODEL=os.environ.get("EMBEDDING_MODEL","sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
model=TextEmbedding(model_name=MODEL,cache_dir=os.environ["EMBEDDING_CACHE"],threads=2,local_files_only=True)
lock=threading.Lock()
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def response(self,status,data):
        body=json.dumps(data).encode();self.send_response(status)
        self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(body)))
        self.end_headers();self.wfile.write(body)
    def do_GET(self):
        self.response(200,{"status":"ok","model":MODEL}) if self.path=="/health" else self.response(404,{"error":"Not found"})
    def do_POST(self):
        if self.path!="/v1/embeddings":return self.response(404,{"error":"Not found"})
        try:
            size=int(self.headers.get("Content-Length","0"))
            if size<1 or size>1024*1024:return self.response(413,{"error":"Request too large"})
            data=json.loads(self.rfile.read(size));texts=data["input"]
            if isinstance(texts,str):texts=[texts]
            if data.get("model")!=MODEL or not isinstance(texts,list) or not 1<=len(texts)<=128 or any(not isinstance(t,str) or len(t)>16000 for t in texts):
                return self.response(400,{"error":"Invalid model or input"})
            with lock:vectors=[v.tolist() for v in model.embed(texts)]
            self.response(200,{"object":"list","model":MODEL,"data":[{"object":"embedding","index":i,"embedding":v} for i,v in enumerate(vectors)],"usage":{"prompt_tokens":0,"total_tokens":0}})
        except Exception:
            self.response(500,{"error":"Embedding failed"})
if __name__=="__main__":
    ThreadingHTTPServer(("127.0.0.1",int(os.environ.get("EMBEDDING_PORT","18128"))),Handler).serve_forever()

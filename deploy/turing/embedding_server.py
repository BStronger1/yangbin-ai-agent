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
            self.connection.settimeout(30)
            limit=1024*1024
            if self.headers.get("Transfer-Encoding","").lower()=="chunked":
                if self.headers.get("Content-Length"):return self.response(400,{"error":"Ambiguous request framing"})
                parts=[];total=0
                while True:
                    line=self.rfile.readline(128)
                    if not line.endswith(b"\r\n"):return self.response(400,{"error":"Invalid chunk header"})
                    size=int(line.split(b";",1)[0].strip(),16)
                    if size<0 or total+size>limit:return self.response(413,{"error":"Request too large"})
                    if size==0:
                        # The response closes this connection; trailers are not reused.
                        break
                    part=self.rfile.read(size)
                    if len(part)!=size or self.rfile.read(2)!=b"\r\n":return self.response(400,{"error":"Invalid chunk body"})
                    parts.append(part);total+=size
                raw=b"".join(parts)
            else:
                size=int(self.headers.get("Content-Length","0"))
                if size<1 or size>limit:return self.response(413,{"error":"Request too large"})
                raw=self.rfile.read(size)
            data=json.loads(raw);texts=data["input"]
            if isinstance(texts,str):texts=[texts]
            if data.get("model")!=MODEL or not isinstance(texts,list) or not 1<=len(texts)<=128 or any(not isinstance(t,str) or len(t)>16000 for t in texts):
                return self.response(400,{"error":"Invalid model or input"})
            with lock:vectors=[v.tolist() for v in model.embed(texts)]
            self.response(200,{"object":"list","model":MODEL,"data":[{"object":"embedding","index":i,"embedding":v} for i,v in enumerate(vectors)],"usage":{"prompt_tokens":0,"total_tokens":0}})
        except Exception:
            self.response(500,{"error":"Embedding failed"})
if __name__=="__main__":
    ThreadingHTTPServer(("127.0.0.1",int(os.environ.get("EMBEDDING_PORT","18128"))),Handler).serve_forever()

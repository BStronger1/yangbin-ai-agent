"""Package built Java/Vue and source-only pi runtime for the personal updater; no runtime data or secrets."""
import argparse,hashlib,json,re,subprocess,tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--label',required=True);p.add_argument('--output',type=Path,default=ROOT/'target/releases');a=p.parse_args()
 if not re.fullmatch('[a-z0-9-]{1,50}',a.label):p.error('label must be lowercase letters, digits and hyphens')
 files={'app.jar':ROOT/'target/yangbin-ai-agent-0.0.1-SNAPSHOT.jar'}
 for f in (ROOT/'deploy/turing').iterdir():
  if f.is_file() and (f.suffix in ('.py','.sh') or f.name=='requirements.txt'):files['deploy/turing/'+f.name]=f
 for f in (ROOT/'yangbin-ai-agent-frontend/dist').rglob('*'):
  if f.is_file():files['frontend/'+f.relative_to(ROOT/'yangbin-ai-agent-frontend/dist').as_posix()]=f
 runtime={}
 for folder in ('src','test'):
  for f in (ROOT/'coding-agent'/folder).rglob('*'):
   if f.is_file():runtime[f.relative_to(ROOT/'coding-agent').as_posix()]=f
 for name in ('package.json','package-lock.json','README.md','PI-LICENSE'):runtime[name]=ROOT/'coding-agent'/name
 if not files['app.jar'].is_file() or 'frontend/index.html' not in files:p.error('Build Java and frontend before packaging')
 fingerprint={n:hashlib.sha256(f.read_bytes()).hexdigest() for n,f in {**files,**{'coding/'+n:f for n,f in runtime.items()}}.items()}
 revision=a.label+'-'+hashlib.sha256(json.dumps(fingerprint,sort_keys=True).encode()).hexdigest()[:12]
 prefix='runtime/coding-releases/'+revision
 files.update({prefix+'/'+n:f for n,f in runtime.items()})
 manifest={'releaseId':revision,'baseline':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'workingTree':True,'narrativeEmbed':True,'codingRuntime':{'path':prefix},'feature':'per-account BYOK for chat and pi coding agent','sha256':{n:hashlib.sha256(f.read_bytes()).hexdigest() for n,f in files.items()}}
 a.output.mkdir(parents=True,exist_ok=True);m=a.output/'release.json';m.write_text(json.dumps(manifest,indent=2),encoding='utf-8');archive=a.output/(revision+'.tar.gz')
 with tarfile.open(archive,'w:gz') as t:
  for name,f in files.items():t.add(f,arcname=name)
  t.add(m,arcname='release.json')
 print(json.dumps({'releaseId':revision,'archive':str(archive),'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()}))
if __name__=='__main__':main()

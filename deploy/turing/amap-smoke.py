"""Operator-only Amap acceptance; reads private credentials, never prints them."""
from pathlib import Path
import json,sys,datetime,urllib.parse,urllib.request
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'deploy/turing'))
from session_client import SessionClient
cfg=json.loads((ROOT/'secrets.json').read_text());e=cfg['env'];key=e.get('AMAP_API_KEY','')
assert key,'Amap key not configured'
assert (ROOT/'secrets.json').stat().st_mode & 0o077 == 0,'Private configuration permissions too broad'
code='network_failure'
try:
 url='https://restapi.amap.com/v3/geocode/geo?'+urllib.parse.urlencode({'key':key,'address':'上海市'})
 with urllib.request.urlopen(url,timeout=20) as response:raw=json.load(response)
 code=raw.get('infocode','unknown');code=code if str(code).isdigit() else 'unknown'
except Exception:pass
base='http://'+e['PUBLIC_BIND']+':'+e['PUBLIC_PORT'];client=SessionClient(base,e['ACCESS_USER'],e['ACCESS_PASSWORD'])
date=(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()+datetime.timedelta(days=1)).isoformat()
evidence=client.call('/api/ai/plans/evidence?'+urllib.parse.urlencode({'city':'上海','date':date}))
serialized=json.dumps(evidence,ensure_ascii=False);assert key not in serialized and 'key=' not in serialized
assert evidence['weatherStatus']=='available','Weather regression'
assert evidence['placesStatus']=='available','No live or fallback places available'
amap=all(p['id'].startswith('amap/') for p in evidence['places'])
if amap:
 assert evidence['location']['coordinateSystem']=='GCJ-02'
 assert all(p['coordinateSystem']=='GCJ-02' and p['url'].startswith('https://uri.amap.com/marker?') for p in evidence['places'])
else:
 assert any(n.startswith('高德暂不可用') for n in evidence['notices'])
 assert all(p['id'].startswith(('node/','way/','relation/')) for p in evidence['places'])
report={'private_configuration':True,'upstream_geocode_code':code,'weather':evidence['weatherStatus'],'places':len(evidence['places']),'amap_live':amap,'fallback_live':not amap,'no_secret_in_response':True}
(ROOT/'logs/amap-verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report),flush=True)

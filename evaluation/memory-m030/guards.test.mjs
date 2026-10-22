import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {verifyFrozen,sha256} from './guards.mjs';
test('M030-G1 rejects changed evaluator, runtime, legacy helper and installed SDK before execution',async t=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'m030-guards-'));t.after(async()=>{assert.equal(path.dirname(root),path.resolve(os.tmpdir()));assert.ok(path.basename(root).startsWith('m030-guards-'));await fs.rm(root,{recursive:true,force:true});});
 const here=path.join(root,'memory-m030'),runtime=path.join(root,'runtime'),legacy=path.join(root,'memory-m029');
 await fs.mkdir(here);await fs.mkdir(legacy);await fs.mkdir(path.join(runtime,'node_modules/@earendil-works/pi-coding-agent'),{recursive:true});
 const paths=[path.join(here,'run.mjs'),path.join(runtime,'agent.mjs'),path.join(legacy,'core.mjs')];
 for(const file of paths)await fs.writeFile(file,'original');
 const pi=path.join(runtime,'node_modules/@earendil-works/pi-coding-agent/package.json');await fs.writeFile(pi,'{"version":"1.3.0"}');
 await fs.writeFile(path.join(here,'frozen.json'),JSON.stringify({piVersion:'1.3.0',files:{'run.mjs':sha256('original')},runtime:{'agent.mjs':sha256('original')},legacyCoreSha256:sha256('original')}));
 await verifyFrozen(here,runtime);
 for(const file of paths){await fs.writeFile(file,'changed');await assert.rejects(verifyFrozen(here,runtime),/changed/);await fs.writeFile(file,'original');}
 await fs.writeFile(pi,'{"version":"1.4.0"}');await assert.rejects(verifyFrozen(here,runtime),/differs/);
});

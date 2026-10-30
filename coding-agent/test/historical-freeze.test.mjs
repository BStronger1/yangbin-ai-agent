import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {verifyFrozen} from '../../evaluation/memory-m030/guards.mjs';
const runtime=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
test('M030 frozen live runner refuses changed checkout instead of replaying historic authorization',async()=>{
 // Linux LF checkouts can fail the evaluator byte freeze before the runtime check.
 // Either precise freeze failure must prevent replay; unrelated errors still fail this test.
 await assert.rejects(verifyFrozen(path.resolve(runtime,'../evaluation/memory-m030'),runtime),/Frozen (runtime|evaluator) changed/);
});

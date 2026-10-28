import test from 'node:test';
import assert from 'node:assert/strict';
import {localPath} from '../src/local-directory.js';
test('screenshot path boundary: current project allowed, parent still denied',()=>{
  assert.deepEqual(localPath('.',true),[]);assert.throws(()=>localPath('..',true),/路径越界/);
  assert.deepEqual(localPath('index.html'),['index.html']);assert.throws(()=>localPath('../index.html'));
});

import test from 'node:test';
import assert from 'node:assert/strict';
import {assertCompletedAssistant} from '../src/completion.mjs';
test('only a normally terminated visible final answer completes the turn',()=>{
  assert.doesNotThrow(()=>assertCompletedAssistant({stopReason:'stop',content:[{type:'text',text:'已完成修改，未运行命令。'}]}));
  for(const message of [null,{stopReason:'length',content:[{type:'text',text:'unfinished'}]},{stopReason:'stop',content:[]},{stopReason:'stop',content:[{type:'text',text:'  '}]},{stopReason:'toolUse',content:[{type:'toolCall'}]},{stopReason:'stop',content:[{type:'toolCall'},{type:'text',text:'not final'}]}]) assert.throws(()=>assertCompletedAssistant(message));
});

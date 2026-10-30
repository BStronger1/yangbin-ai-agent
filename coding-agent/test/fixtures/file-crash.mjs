import {ToolRuntime} from '../../src/tools.mjs';
const [workspace,journal,identityJson,stage]=process.argv.slice(2);
const tools=new ToolRuntime({workspace,approvalMode:'allow',execution:{fileJournalDir:journal,identity:JSON.parse(Buffer.from(identityJson,'base64').toString('utf8'))},operationCheckpoint:async point=>{if(point===stage)process.kill(process.pid,'SIGKILL')}});
await tools.init();await tools.execute('edit',{path:'main.txt',oldText:'base',newText:'base+once'},'crash-operation');
// No fixture stage can be enabled by a production environment variable or HTTP input.

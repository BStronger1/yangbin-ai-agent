import {appendFile} from 'node:fs/promises';
import {nativeDirectory} from '../../native-directory.mjs';
import {CandidateWorkspace} from '../../candidate-workspace.mjs';

const [repo,state,id,stop,marker]=process.argv.slice(2);
const journal=new CandidateWorkspace({directory:await nativeDirectory(repo),state,identity:['test-origin','owner','project',repo,'session','turn'],fault:async phase=>{
  if(phase==='written')await appendFile(marker,'1\n');
  if(phase===stop){process.send({phase});await new Promise(()=>{});}
}});
// Keep a real writer process and its active authority until the parent kills it.
const timer=setInterval(()=>{},60000);
try {
  await journal.read({path:'orders.py'});
  await journal.propose('edit',{path:'orders.py',oldText:'rows[:-1]',newText:'rows'},id,async()=>{});
} finally {clearInterval(timer);}

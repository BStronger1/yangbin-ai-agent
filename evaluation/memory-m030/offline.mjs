import http from 'node:http';
// Deliberately synthetic response generator. These outputs never count as quality.
export async function offlineServer(responder) {
 const requests=[];
 const server=http.createServer(async(req,res)=>{try{
  let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);
  const summary=body.messages.some(m=>['system','developer'].includes(m.role)&&JSON.stringify(m.content).includes('You are a context summarization assistant.'));
  const entry={body,summary,bytes:Buffer.byteLength(raw)};requests.push(entry);
  const value=await responder(body,{summary,index:requests.length});const answer=typeof value==='string'?{text:value}:value;
  res.writeHead(200,{'Content-Type':'text/event-stream'});
  const event=(delta,finish_reason=null)=>({id:'m030-offline-'+requests.length,object:'chat.completion.chunk',model:'offline',choices:[{index:0,delta,finish_reason}]});
  const delta={role:'assistant',content:answer.text??''};
  if(answer.tools)delta.tool_calls=answer.tools.map((tool,index)=>({index,id:'m030-call-'+requests.length+'-'+index,type:'function',function:{name:tool.name,arguments:JSON.stringify(tool.arguments)}}));
  if(answer.thinking)delta.reasoning_content=answer.thinking;
  res.write('data: '+JSON.stringify(event(delta))+'\n\n');
  res.write('data: '+JSON.stringify({...event({},answer.finishReason??(answer.tools?'tool_calls':'stop')),usage:{prompt_tokens:Math.ceil(Buffer.byteLength(raw)/4),completion_tokens:20}})+'\n\n');res.end('data: [DONE]\n\n');
 }catch{res.writeHead(500);res.end();}});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 return {url:'http://127.0.0.1:'+server.address().port+'/v1',requests,close:async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}};
}

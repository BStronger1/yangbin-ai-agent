import http from 'node:http';

// Only the explicitly selected offline mode constructs this loopback server.
export async function offlineServer(respond) {
  const requests=[];
  const server=http.createServer(async(request,response)=>{
    let raw='';for await(const part of request)raw+=part;
    const body=JSON.parse(raw);
    const summary=body.messages.some(m=>['system','developer'].includes(m.role)&&JSON.stringify(m.content).includes('You are a context summarization assistant.'));
    requests.push({body,bytes:Buffer.byteLength(raw),summary});
    let supplied=respond?await respond(body,{summary,index:requests.length}):summary?'## Progress\nRoutine work complete.\n## Next\nFollow latest task.':'{"offline":true}';
    const answer=typeof supplied==='string'?{text:supplied}:supplied;
    response.writeHead(200,{'Content-Type':'text/event-stream'});
    const chunk=(delta,finish_reason=null)=>({id:'v3-offline-'+requests.length,object:'chat.completion.chunk',model:'offline',choices:[{index:0,delta,finish_reason}]});
    if(answer.thinking)response.write('data: '+JSON.stringify(chunk({role:'assistant',reasoning_content:answer.thinking}))+'\n\n');
    response.write('data: '+JSON.stringify(chunk({role:'assistant',content:answer.text??''}))+'\n\n');
    response.write('data: '+JSON.stringify({...chunk({},answer.finishReason??'stop'),usage:answer.usage??{prompt_tokens:Math.ceil(Buffer.byteLength(raw)/4),completion_tokens:20}})+'\n\n');
    response.end('data: [DONE]\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  return {url:'http://127.0.0.1:'+server.address().port+'/v1',requests,close:async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}};
}

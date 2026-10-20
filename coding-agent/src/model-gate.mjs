import { randomUUID } from 'node:crypto';
const MAX_EVENT_CHARS=1024*1024, MAX_RESPONSE_BYTES=8*1024*1024;

// Admission covers every HTTP model request, including pi compaction calls.
// SDK cost=0 and zero-filled usage defaults are never treated as billing evidence.
export class ModelGate {
  constructor({ emit = () => {}, signal, required = false, timeoutMs = 10000 } = {}) {
    this.count=0; this.emit=emit; this.signal=signal; this.required=required; this.timeoutMs=timeoutMs; this.pending=new Map();
    signal?.addEventListener('abort',()=>this.close(),{once:true});
  }
  permission(command) { const pending=this.pending.get(command.requestId); if (pending) pending(command.approved===true); }
  close() { for (const resolve of this.pending.values()) resolve(false); this.pending.clear(); }
  async admit(init) {
    this.signal?.throwIfAborted();
    if(++this.count>100)throw new Error('Model request limit reached (100 per turn, including compaction)');
    const body=typeof init?.body==='string'?init.body:'';
    let parsed; try { parsed=JSON.parse(body); } catch { throw new Error('Model request body must be JSON for quota accounting'); }
    const maxTokens=Number(parsed.max_tokens ?? parsed.max_completion_tokens ?? 8192);
    if(!Number.isFinite(maxTokens)||maxTokens<=0||maxTokens>65536)throw new Error('Invalid model output token limit');
    const requestId=randomUUID(), reservedTokens=Buffer.byteLength(body,'utf8')+Math.ceil(maxTokens);
    if(this.required) {
      const approved=await new Promise(resolve=>{
        const settle=value=>{clearTimeout(timer);this.pending.delete(requestId);resolve(value);};
        const timer=setTimeout(()=>settle(false),this.timeoutMs);this.pending.set(requestId,settle);
        this.emit({type:'model_request',requestId,reservedTokens});
      });
      if(!approved)throw new Error('Model request denied or admission timed out');
    }
    this.signal?.throwIfAborted(); return requestId;
  }
  wrap(fetch) {
    return async (input,init) => {
      const requestId=await this.admit(init); let completed=false;
      let inputTokens=null,outputTokens=null,ended=false,protocolError=false,buffer='',responseBytes=0;
      const finish=status=>{if(completed)return;completed=true;this.emit({type:'model_usage',requestId,inputTokens,outputTokens,status});};
      const terminalStatus=()=>protocolError?'FAILED':ended?'SUCCEEDED':'UNKNOWN';
      const count=value=>Number.isSafeInteger(value)&&value>=0?value:null;
      const line=raw=>{
        if(raw.length>MAX_EVENT_CHARS)throw new Error('Model stream event exceeds limit');
        if(!raw.startsWith('data:'))return;const text=raw.slice(5).trim();if(text==='[DONE]'){ended=true;return;}
        let item;try{item=JSON.parse(text);}catch{return;}
        if(item.type==='error'||item.error)protocolError=true;
        const usage=item.usage??item.message?.usage;
        if(usage){
          if(count(usage.prompt_tokens)!==null)inputTokens=count(usage.prompt_tokens);
          if(count(usage.completion_tokens)!==null)outputTokens=count(usage.completion_tokens);
          if(count(usage.input_tokens)!==null)inputTokens=usage.input_tokens+(count(usage.cache_read_input_tokens)??0)+(count(usage.cache_creation_input_tokens)??0);
          if(count(usage.output_tokens)!==null)outputTokens=usage.output_tokens;
        }
        if(item.type==='message_stop')ended=true;
      };
      try {
        const response=await fetch(input,init);
        if(!response.ok||!response.body){finish('FAILED');return response;}
        const reader=response.body.getReader(),decoder=new TextDecoder();
        const body=new ReadableStream({
          async pull(controller){
            try{
              const item=await reader.read();
              if(item.done){buffer+=decoder.decode();if(buffer)line(buffer);finish(terminalStatus());controller.close();return;}
              responseBytes+=item.value.byteLength;
              if(responseBytes>MAX_RESPONSE_BYTES)throw new Error('Model response exceeds 8 MiB limit');
              buffer+=decoder.decode(item.value,{stream:true});
              let index;while((index=buffer.indexOf('\n'))>=0){line(buffer.slice(0,index).replace(/\r$/,''));buffer=buffer.slice(index+1);}
              if(buffer.length>MAX_EVENT_CHARS)throw new Error('Model stream event exceeds limit');
              controller.enqueue(item.value);
            }catch(error){finish(terminalStatus());await reader.cancel().catch(()=>{});controller.error(error);}
          },
          // Provider SDKs cancel after consuming their terminal marker instead of reading EOF.
          // Execution completion is independent of whether the provider supplied usage.
          async cancel(reason){finish(terminalStatus());await reader.cancel(reason);},
        });
        return new Response(body,{status:response.status,statusText:response.statusText,headers:response.headers});
      }catch(error){finish('FAILED');throw error;}
    };
  }
}

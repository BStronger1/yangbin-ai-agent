package com.yangbin.yangbinaiagent.tools;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import java.util.*;
import java.util.function.Consumer;
public final class TracedTools {
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(TracedTools.class);
 private TracedTools(){}
 public static ToolCallback[] wrap(ToolCallback[] tools,Consumer<Map<String,Object>> events){
  return Arrays.stream(tools).map(tool->new ToolCallback(){
   public ToolDefinition getToolDefinition(){return tool.getToolDefinition();}
   public ToolMetadata getToolMetadata(){return tool.getToolMetadata();}
   public String call(String input){return call(input,null);}
   public String call(String input,ToolContext context){
    if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Tool execution cancelled");
    String id=UUID.randomUUID().toString(),name=getToolDefinition().name();long start=System.nanoTime();
    String requestId=org.slf4j.MDC.get("requestId");
    if(requestId==null||!requestId.matches("[a-f0-9]{32}"))requestId="unlinked";
    String safeName=name.matches("[A-Za-z0-9_-]{1,80}")?name:"external_tool";
    events.accept(Map.of("id",id,"type","tool","name",name,"status","running","arguments",clip(input)));
    try {String result=context==null?tool.call(input):tool.call(input,context);
     boolean failed=result!=null&&(result.startsWith("Error")||result.startsWith("错误")||result.matches("(?s).*\\\"isError\\\"\\s*:\\s*true.*"));
     events.accept(Map.of("id",id,"type","tool","name",name,"status",failed?"failed":"completed","arguments",clip(input),"result",clip(result),"durationMs",(System.nanoTime()-start)/1000000));
     LOG.info("event=tool_complete requestId={} toolCallId={} tool={} status={} durationMs={}",requestId,id,safeName,failed?"failed":"completed",(System.nanoTime()-start)/1000000);return result;
    }catch(RuntimeException ex){LOG.warn("event=tool_complete requestId={} toolCallId={} tool={} status=failed durationMs={}",requestId,id,safeName,(System.nanoTime()-start)/1000000);events.accept(Map.of("id",id,"type","tool","name",name,"status","failed","durationMs",(System.nanoTime()-start)/1000000,"result","工具执行失败"));throw ex;}
   }
  }).toArray(ToolCallback[]::new);
 }
 private static String clip(String s){return s==null?"":s.length()>3000?s.substring(0,3000)+"…[已截断]":s;}
}

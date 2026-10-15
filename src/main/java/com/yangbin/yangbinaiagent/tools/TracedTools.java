package com.yangbin.yangbinaiagent.tools;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import java.util.*;
import java.util.function.Consumer;
public final class TracedTools {
 private TracedTools(){}
 public static ToolCallback[] wrap(ToolCallback[] tools,Consumer<Map<String,Object>> events){
  return Arrays.stream(tools).map(tool->new ToolCallback(){
   public ToolDefinition getToolDefinition(){return tool.getToolDefinition();}
   public ToolMetadata getToolMetadata(){return tool.getToolMetadata();}
   public String call(String input){return call(input,null);}
   public String call(String input,ToolContext context){
    String id=UUID.randomUUID().toString(),name=getToolDefinition().name();long start=System.nanoTime();
    events.accept(Map.of("id",id,"type","tool","name",name,"status","running","arguments",clip(input)));
    try {String result=context==null?tool.call(input):tool.call(input,context);
     boolean failed=result!=null&&(result.startsWith("Error")||result.startsWith("错误")||result.matches("(?s).*\\\"isError\\\"\\s*:\\s*true.*"));
     events.accept(Map.of("id",id,"type","tool","name",name,"status",failed?"failed":"completed","arguments",clip(input),"result",clip(result),"durationMs",(System.nanoTime()-start)/1000000));return result;
    }catch(RuntimeException ex){events.accept(Map.of("id",id,"type","tool","name",name,"status","failed","durationMs",(System.nanoTime()-start)/1000000,"result","工具执行失败"));throw ex;}
   }
  }).toArray(ToolCallback[]::new);
 }
 private static String clip(String s){return s==null?"":s.length()>3000?s.substring(0,3000)+"…[已截断]":s;}
}

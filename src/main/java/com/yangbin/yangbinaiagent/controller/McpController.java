package com.yangbin.yangbinaiagent.controller;
import com.fasterxml.jackson.databind.*;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/ai/mcp")
public class McpController {
 private final ObjectProvider<SyncMcpToolCallbackProvider> provider;
 private final ObjectMapper json;
 private final Map<String,Deque<Map<String,Object>>> histories=new java.util.concurrent.ConcurrentHashMap<>();
 private Deque<Map<String,Object>> history(){return histories.computeIfAbsent(com.yangbin.yangbinaiagent.security.UserContext.current().id(),id->new ArrayDeque<>());}
 public McpController(ObjectProvider<SyncMcpToolCallbackProvider> p,ObjectMapper j){provider=p;json=j;}
 private ToolCallback[] callbacks(){var p=provider.getIfAvailable();return p==null?new ToolCallback[0]:p.getToolCallbacks();}
 private boolean testable(String name){return name.endsWith("_currentTime")||name.endsWith("_listKnowledgeDocuments")||name.endsWith("_readKnowledgeDocument");}
 @GetMapping public Object list(){return Map.of("tools",Arrays.stream(callbacks()).map(t->Map.of("name",t.getToolDefinition().name(),"description",t.getToolDefinition().description(),"schema",t.getToolDefinition().inputSchema(),"testable",testable(t.getToolDefinition().name()))).toList(),"status",callbacks().length>0?"DISCOVERED":"UNAVAILABLE","note","已发现工具不等于连接实时健康；请运行测试确认");}
 public record Call(String name,JsonNode arguments){}
 @PostMapping("/call") public Object call(@RequestBody Call request)throws Exception{
   ToolCallback tool=Arrays.stream(callbacks()).filter(t->t.getToolDefinition().name().equals(request.name())).findFirst().orElseThrow(()->new IllegalArgumentException("工具不存在"));
   if(!testable(request.name()))throw new IllegalArgumentException("此工具不开放手动测试");
   JsonNode args=request.arguments();if(args==null||!args.isObject()||args.toString().length()>8000)throw new IllegalArgumentException("参数必须是 JSON 对象，最多 8000 字符");
   JsonNode schema=json.readTree(tool.getToolDefinition().inputSchema());
   for(var field:schema.path("required"))if(!args.has(field.asText()))throw new IllegalArgumentException("缺少参数："+field.asText());
   var fields=args.fields();while(fields.hasNext()){var f=fields.next();if(!schema.path("properties").has(f.getKey()))throw new IllegalArgumentException("未知参数："+f.getKey());if(!f.getValue().isTextual())throw new IllegalArgumentException("参数需要字符串："+f.getKey());}
   long start=System.nanoTime();boolean ok=true;String result;
   try {result=tool.call(args.toString());try{ok=!json.readTree(result).path("isError").asBoolean(false);}catch(Exception ignored){} if(result.startsWith("Error")||result.startsWith("错误"))ok=false;}
   catch(Exception e){ok=false;result="工具调用失败，请检查参数和 MCP 服务";}
   var entry=Map.<String,Object>of("id",UUID.randomUUID().toString(),"name",request.name(),"arguments",args,"success",ok,"result",result.length()>16000?result.substring(0,16000)+"\n[结果已截断]":result,"durationMs",(System.nanoTime()-start)/1000000,"time",java.time.Instant.now().toString());
   var history=history();synchronized(history){history.addFirst(entry);while(history.size()>50)history.removeLast();}return entry;
 }
 @GetMapping("/history") public Object listHistory(){var history=history();synchronized(history){return List.copyOf(history);}}
}

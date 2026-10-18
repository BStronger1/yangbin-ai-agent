package com.yangbin.yangbinaiagent.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.yangbin.yangbinaiagent.mcp.McpWorkbench;
import com.yangbin.yangbinaiagent.work.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController @RequestMapping("/ai/mcp")
public class McpController {
 private final McpWorkbench service;private final PartnerResources resources;private final WorkService work;
 public McpController(McpWorkbench service,PartnerResources resources,WorkService work){this.service=service;this.resources=resources;this.work=work;}
 public record Call(String name,JsonNode arguments,String requestId){}
 public record Attach(String partnerId){}
 @GetMapping public Object list(){return service.catalog();}
 @PostMapping("/call") public Object call(@RequestBody Call request)throws Exception{if(request==null)throw new IllegalArgumentException("缺少调用参数");return service.call(request.requestId(),request.name(),request.arguments());}
 @GetMapping("/history") public Object history()throws Exception{return service.history();}
 @GetMapping("/history/{id}") public Object get(@PathVariable String id)throws Exception{return service.get(id);}
 @DeleteMapping("/history") public Object clear()throws Exception{service.clear();return Map.of("cleared",true);}
 @GetMapping("/history/{id}/download") public ResponseEntity<byte[]> download(@PathVariable String id)throws Exception{return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=mcp-"+UUID.fromString(id)+".md").header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(service.markdown(id).getBytes(StandardCharsets.UTF_8));}
 @PostMapping("/history/{id}/attach") public Object attach(@PathVariable String id,@RequestBody Attach input)throws Exception{
  if(input==null||input.partnerId()==null)throw new IllegalArgumentException("请选择工作伙伴");
  if(!work.policy(input.partnerId()).enabled())throw new IllegalStateException("此伙伴已被限制");
  var entry=service.get(id);if(!entry.success())throw new IllegalArgumentException("只能引用成功的工具结果");
  return resources.importMcp(input.partnerId(),entry.id(),service.markdown(id));
 }
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null?"参数无效":e.getMessage()));}
 @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()==null?"操作暂不可用":e.getMessage()));}
 @ExceptionHandler(Exception.class) public ResponseEntity<?> failed(Exception e){return ResponseEntity.status(503).body(Map.of("message","MCP 服务暂未完成，请刷新记录确认状态，不会自动重试工具调用。"));}
}

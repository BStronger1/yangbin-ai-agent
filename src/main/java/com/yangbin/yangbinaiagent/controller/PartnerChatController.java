package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.work.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
@RestController
@RequestMapping("/ai/work/partners/{partnerId}")
public class PartnerChatController {
 private final PartnerChatService service; private final PartnerResources resources; private final WorkService work;
 public PartnerChatController(PartnerChatService service,PartnerResources resources,WorkService work){this.service=service;this.resources=resources;this.work=work;}
 @GetMapping("/profile") public PartnerChatService.Profile profile(@PathVariable String partnerId)throws Exception{return service.profile(partnerId);}
 @PutMapping("/profile") public PartnerChatService.Profile save(@PathVariable String partnerId,@RequestBody PartnerChatService.Profile input)throws Exception{return service.saveProfile(partnerId,input);}
 @GetMapping("/conversations") public List<PartnerChatService.Summary> list(@PathVariable String partnerId)throws Exception{return service.list(partnerId);}
 @GetMapping("/conversations/{id}") public PartnerChatService.Conversation get(@PathVariable String partnerId,@PathVariable String id)throws Exception{return service.get(partnerId,id);}
 @DeleteMapping("/conversations/{id}") public Map<String,Boolean> delete(@PathVariable String partnerId,@PathVariable String id,@RequestParam int revision)throws Exception{service.deleteConversation(partnerId,id,revision);return Map.of("deleted",true);}
 @PostMapping("/messages") public PartnerChatService.Conversation send(@PathVariable String partnerId,@RequestBody PartnerChatService.Input input)throws Exception{return service.send(partnerId,input);}
 @GetMapping("/conversations/{id}/page") public PartnerChatService.Page page(@PathVariable String partnerId,@PathVariable String id,@RequestParam(required=false) Integer before,@RequestParam(defaultValue="8") int limit)throws Exception{return service.page(partnerId,id,before,limit);}
 @PostMapping("/jobs") public ResponseEntity<?> submit(@PathVariable String partnerId,@RequestBody PartnerChatService.Input input)throws Exception{service.submit(partnerId,input);return ResponseEntity.accepted().body(service.page(partnerId,input.conversationId(),null,8));}
 @PostMapping("/conversations/{id}/stop") public PartnerChatService.Page stop(@PathVariable String partnerId,@PathVariable String id)throws Exception{service.cancel(partnerId,id);return service.page(partnerId,id,null,8);}
 @PostMapping("/files") public PartnerResources.Resource upload(@PathVariable String partnerId,@RequestParam MultipartFile file)throws Exception{if(!work.policy(partnerId).enabled())throw new IllegalStateException("伙伴已被限制");return resources.upload(partnerId,file);}
 @GetMapping("/resources") public List<PartnerResources.Resource> resources(@PathVariable String partnerId,@RequestParam(required=false) String conversation)throws Exception{var all=new ArrayList<>(resources.list(partnerId,conversation));all.addAll(service.legacyResources(partnerId,conversation));return all;}
 @GetMapping("/resources/{id}/content") public ResponseEntity<byte[]> content(@PathVariable String partnerId,@PathVariable String id,@RequestParam(defaultValue="false") boolean preview)throws Exception{PartnerResources.Resource r;byte[] bytes;try{r=resources.get(partnerId,id);bytes=resources.bytes(partnerId,id);}catch(IllegalArgumentException e){r=service.legacyResources(partnerId,null).stream().filter(x->x.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("资源不存在"));bytes=service.legacyContent(partnerId,id);}boolean inline=preview&&(r.mime().equals("image/png")||r.mime().equals("image/jpeg"));return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.mime())).header(HttpHeaders.CONTENT_DISPOSITION,(inline?ContentDisposition.inline():ContentDisposition.attachment()).filename(r.name(),StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store").body(bytes);}
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
 @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));}
 @ExceptionHandler(Exception.class) public ResponseEntity<?> error(Exception e){return ResponseEntity.status(503).body(Map.of("message","对话服务暂未完成，请刷新确认状态；不会自动重试。"));}
}

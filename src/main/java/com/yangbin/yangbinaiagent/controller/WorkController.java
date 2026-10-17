package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.work.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/ai/work")
public class WorkController {
    private final WorkCatalog catalog;
    private final WorkService service;
    private final PartnerChatService chat;
    public WorkController(WorkCatalog catalog,WorkService service,PartnerChatService chat) { this.catalog=catalog; this.service=service; this.chat=chat; }
    @GetMapping("/catalog") public WorkCatalog.Catalog catalog() { UserContext.current(); return catalog.all(); }
    @GetMapping("/library") public WorkService.Library library() throws Exception { return service.library(); }
    public record Selection(boolean selected) {}
    @PutMapping("/library/{kind}/{id}")
    public WorkService.Library select(@PathVariable String kind,@PathVariable String id,@RequestBody Selection input) throws Exception {
        return service.select(kind,id,input.selected());
    }
    @GetMapping("/partners") public List<String> summoned() throws Exception { return service.summoned(); }
    @PostMapping("/partners/{id}/summon") public WorkService.Library summon(@PathVariable String id) throws Exception { return service.select("partner",id,true); }
    @GetMapping("/partners/{id}") public Map<String,Object> detail(@PathVariable String id) throws Exception { var result=new LinkedHashMap<>(service.partnerDetail(id)); result.put("instructions",chat.instructions(id)); return result; }
    @GetMapping("/partners/{id}/access") public WorkService.PartnerPolicy access(@PathVariable String id) throws Exception { return service.policy(id); }
    @PutMapping("/partners/{id}/policy") public WorkService.PartnerPolicy policy(@PathVariable String id,@RequestBody WorkService.PartnerPolicy input) throws Exception { return service.policy(id,input); }
    @GetMapping("/runs") public List<WorkService.Summary> list() throws Exception { return service.list(); }
    @GetMapping("/runs/{id}") public WorkService.Run get(@PathVariable String id) throws Exception { return service.get(id); }
    @PostMapping("/runs") public WorkService.Run execute(@RequestBody WorkService.Request input) throws Exception { return service.execute(input); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failed(Exception e) {
        return ResponseEntity.status(503).body(Map.of("message","工作服务暂未完成，请刷新任务记录确认状态；不会自动重新生成。"));
    }
}

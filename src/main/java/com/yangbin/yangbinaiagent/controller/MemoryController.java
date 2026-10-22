package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.memory.MemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/ai/coding/projects/{projectId}/memory")
public class MemoryController {
 private final MemoryService service;
 public MemoryController(MemoryService service){this.service=service;}
 @GetMapping public Object list(@PathVariable String projectId)throws Exception{return service.list(projectId);}
 @GetMapping("/search") public Object search(@PathVariable String projectId,@RequestParam String q)throws Exception{return service.search(projectId,q);}
 @PostMapping public Object create(@PathVariable String projectId,@RequestBody MemoryService.Create input)throws Exception{return MemoryService.publicItem(service.create(projectId,input));}
 @PutMapping("/{id}") public Object update(@PathVariable String projectId,@PathVariable String id,@RequestBody MemoryService.Update input)throws Exception{return MemoryService.publicItem(service.update(projectId,id,input));}
 @DeleteMapping("/{id}") public Object forget(@PathVariable String projectId,@PathVariable String id,@RequestParam int version)throws Exception{return service.forget(projectId,id,version);}
 @PutMapping("/settings") public Object settings(@PathVariable String projectId,@RequestBody MemoryService.Settings input)throws Exception{return service.settings(projectId,input);}
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
 @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));}
 @ExceptionHandler(Exception.class) public ResponseEntity<?> unavailable(Exception e){return ResponseEntity.status(503).body(Map.of("message","项目记忆暂不可用，请刷新后重试"));}
}


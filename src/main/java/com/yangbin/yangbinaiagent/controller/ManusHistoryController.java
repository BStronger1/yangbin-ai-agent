package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.chatmemory.ManusConversations;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestController @RequestMapping("/ai/manus")
public class ManusHistoryController {
 private final ManusConversations conversations;public ManusHistoryController(ManusConversations c){conversations=c;}
 @GetMapping("/conversations") public Object list()throws Exception{return conversations.list();}
 @GetMapping("/conversations/{id}") public Object get(@PathVariable String id)throws Exception{return conversations.get(id);}
 @PostMapping("/conversations/{id}/cancel") public Object cancel(@PathVariable String id)throws Exception{conversations.cancel(id);return conversations.get(id);}
 @DeleteMapping("/conversations/{id}") public Object delete(@PathVariable String id)throws Exception{conversations.delete(id);return Map.of("deleted",true);}
 @GetMapping("/memory") public Object memory()throws Exception{return Map.of("text",conversations.memory());}
 @PutMapping("/memory") public Object memory(@RequestBody Map<String,String> body)throws Exception{conversations.memory(body.get("text"));return memory();}
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
 @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));}
 @ExceptionHandler(Exception.class) public ResponseEntity<?> failed(Exception e){return ResponseEntity.status(503).body(Map.of("message","会话操作未完成，请稍后重试"));}
}

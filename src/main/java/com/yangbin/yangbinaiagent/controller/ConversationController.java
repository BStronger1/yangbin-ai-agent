package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.chatmemory.PersistentChatRepository;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/ai/conversations")
public class ConversationController {
 private final PersistentChatRepository memory;public ConversationController(PersistentChatRepository memory){this.memory=memory;}
 private String key(String id,String mode){if(!Set.of("chat","rag","mcp").contains(mode))throw new IllegalArgumentException("无效会话模式");return UserContext.chatId(id)+":"+mode;}
 @GetMapping public Object list(){return memory.listFor(UserContext.current().id());}
 @GetMapping("/{id}") public Object history(@PathVariable String id,@RequestParam String mode){return memory.history(key(id,mode));}
 @DeleteMapping("/{id}") public Object delete(@PathVariable String id,@RequestParam String mode){memory.deleteByConversationId(key(id,mode));return Map.of("ok",true);}
}

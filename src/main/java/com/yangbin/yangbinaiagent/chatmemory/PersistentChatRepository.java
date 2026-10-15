package com.yangbin.yangbinaiagent.chatmemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;

/** Single-process, atomic JSON storage. Identity is part of the server-generated key. */
@Component
public class PersistentChatRepository implements ChatMemoryRepository {
 public record Entry(String type,String text,List<AssistantMessage.ToolCall> calls,List<ToolResponseMessage.ToolResponse> responses) {}
 public record Conversation(String id,String updatedAt,List<Entry> messages) {}
 private final Path root;private final ObjectMapper json;private final Set<String> busy=new HashSet<>();
 public PersistentChatRepository(ObjectMapper json,@Value("${app.memory.dir:tmp/chat-memory-v2}") String dir)throws Exception{this.json=json;root=Path.of(dir);Files.createDirectories(root);}
 private Path path(String id){if(id==null||!id.matches("(admin|[a-f0-9]{32}):[A-Za-z0-9_-]{1,100}(:(chat|rag|mcp))?"))throw new IllegalArgumentException("无效会话编号");return root.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");}
 private Conversation read(String id){try{Path p=path(id);return Files.exists(p)?json.readValue(p.toFile(),Conversation.class):new Conversation(id,"",List.of());}catch(java.io.IOException e){throw new IllegalStateException("读取会话失败，请联系管理员",e);}}
 public synchronized boolean begin(String id){path(id);return busy.add(id);}
 public synchronized void end(String id){busy.remove(id);}
 public synchronized List<String> findConversationIds(){try(var files=Files.list(root)){var ids=new ArrayList<String>();for(Path p:files.filter(p->p.toString().endsWith(".json")).toList())ids.add(json.readValue(p.toFile(),Conversation.class).id());return ids;}catch(Exception e){throw new IllegalStateException("读取会话目录失败",e);}}
 public synchronized List<Message> findByConversationId(String id){return read(id).messages().stream().map(e->(Message)switch(e.type()){
 case "USER" -> new UserMessage(e.text());case "SYSTEM" -> new SystemMessage(e.text());case "ASSISTANT" -> new AssistantMessage(e.text(),Map.of(),e.calls()==null?List.of():e.calls());case "TOOL" -> new ToolResponseMessage(e.responses());default -> throw new IllegalStateException("未知消息类型");}).toList();}
 public synchronized void saveAll(String id,List<Message> messages){
  Path target=path(id);var entries=messages.stream().map(m->new Entry(m.getMessageType().name(),m.getText(),m instanceof AssistantMessage a?a.getToolCalls():null,m instanceof ToolResponseMessage t?t.getResponses():null)).toList();
  try{Path temp=Files.createTempFile(root,"chat-",".tmp");try{json.writeValue(temp.toFile(),new Conversation(id,Instant.now().toString(),entries));Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}catch(Exception e){throw new IllegalStateException("保存会话失败",e);}
 }
 public synchronized void deleteByConversationId(String id){if(busy.contains(id))throw new IllegalStateException("会话正在生成，请稍后删除");try{Files.deleteIfExists(path(id));}catch(Exception e){throw new IllegalStateException("删除会话失败",e);}}
 public synchronized List<Map<String,Object>> listFor(String owner){return findConversationIds().stream().filter(id->id.startsWith(owner+":")&&id.split(":").length==3).map(this::read).sorted(Comparator.comparing(Conversation::updatedAt).reversed()).limit(100).map(c->{String[] parts=c.id().split(":");String title=c.messages().stream().filter(m->m.type().equals("USER")).map(Entry::text).findFirst().orElse("新会话");return Map.<String,Object>of("id",parts[1],"mode",parts[2],"updatedAt",c.updatedAt(),"title",title.substring(0,Math.min(40,title.length())));}).toList();}
 public synchronized List<Map<String,String>> history(String id){return read(id).messages().stream().filter(m->m.type().equals("USER")||m.type().equals("ASSISTANT")).filter(m->m.text()!=null&&!m.text().isBlank()).map(m->Map.of("role",m.type(),"content",m.text())).toList();}
}

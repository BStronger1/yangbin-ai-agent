package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.chatmemory.PersistentChatRepository;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import java.nio.file.*;import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PersistentMemoryTest {
 @TempDir Path root;
 @Test void restartRestoresMessagesAndSeparatesOwnersAndModes()throws Exception{
  var first=new PersistentChatRepository(new ObjectMapper(),root.toString());String a="admin:same:chat",b="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:same:chat";
  first.saveAll(a,List.of(new UserMessage("只属于管理员"),new AssistantMessage("记住了")));first.saveAll(b,List.of(new UserMessage("用户私有")));
  var restart=new PersistentChatRepository(new ObjectMapper(),root.toString());assertThat(restart.findByConversationId(a)).extracting(Message::getText).containsExactly("只属于管理员","记住了");assertThat(restart.history(b)).hasSize(1);assertThat(restart.history("admin:same:rag")).isEmpty();assertThat(restart.listFor("admin")).hasSize(1);
  restart.deleteByConversationId(b);assertThat(new PersistentChatRepository(new ObjectMapper(),root.toString()).history(b)).isEmpty();assertThat(restart.history(a)).hasSize(2);
 }
 @Test void windowBoundSurvivesRestart()throws Exception{var repo=new PersistentChatRepository(new ObjectMapper(),root.toString());var memory=MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(20).build();for(int i=0;i<30;i++)memory.add("admin:window:chat",new UserMessage("message"+i));var reload=new PersistentChatRepository(new ObjectMapper(),root.toString());assertThat(reload.findByConversationId("admin:window:chat")).hasSize(20).extracting(Message::getText).doesNotContain("message0").contains("message29");}
 @Test void toolCallRoundTripAndTraversalAndBusyDelete()throws Exception{var repo=new PersistentChatRepository(new ObjectMapper(),root.toString());var call=new AssistantMessage.ToolCall("id","function","clock","{}");repo.saveAll("admin:tool:mcp",List.of(new AssistantMessage("",Map.of(),List.of(call)),new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse("id","clock","now")))));assertThat(((AssistantMessage)repo.findByConversationId("admin:tool:mcp").get(0)).getToolCalls()).containsExactly(call);assertThatThrownBy(()->repo.saveAll("../../escape",List.of())).isInstanceOf(IllegalArgumentException.class);assertThat(repo.begin("admin:tool:mcp")).isTrue();assertThat(repo.begin("admin:tool:mcp")).isFalse();assertThatThrownBy(()->repo.deleteByConversationId("admin:tool:mcp")).isInstanceOf(IllegalStateException.class);repo.end("admin:tool:mcp");repo.deleteByConversationId("admin:tool:mcp");}
 @Test void corruptStorageFailsVisiblyInsteadOfSilentlyForgetting()throws Exception{var repo=new PersistentChatRepository(new ObjectMapper(),root.toString());repo.saveAll("admin:x:chat",List.of(new UserMessage("x")));try(var files=Files.list(root)){Files.writeString(files.findFirst().orElseThrow(),"broken");}assertThatThrownBy(()->repo.history("admin:x:chat")).isInstanceOf(IllegalStateException.class);}
}

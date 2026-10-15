package com.yangbin.yangbinaiagent;
import com.yangbin.yangbinaiagent.security.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TenantToolTest {
 ToolCallback tool(String name){return new ToolCallback(){public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();}public String call(String input){return UserContext.current().id();}};}
 @Test void ordinaryUsersCannotReceiveTerminalFileOrNetworkTools(){
  var user=new UserContext.User("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","user");
  var tools=new ToolCallback[]{tool("executeTerminalCommand"),tool("readFile"),tool("scrapeWebPage"),tool("generatePDF"),tool("searchKnowledgeBase")};
  var allowed=ToolAccess.forUser(tools,user);assertThat(allowed).hasSize(2);
  assertThat(allowed[0].call("{}")).isEqualTo(user.id());
  assertThatThrownBy(UserContext::current).isInstanceOf(IllegalStateException.class);
  assertThat(ToolAccess.forUser(tools,UserContext.ADMIN)).hasSize(5);
 }
 @Test void identityScopeRestoresPriorIdentity(){
  try(var ignored=UserContext.open(UserContext.ADMIN)){
   var user=new UserContext.User("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","user");
   assertThat(UserContext.call(user,()->UserContext.current().id())).isEqualTo(user.id());
   assertThat(UserContext.current().id()).isEqualTo("admin");
  }
  assertThatThrownBy(UserContext::current).isInstanceOf(IllegalStateException.class);
 }
}

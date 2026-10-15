package com.yangbin.yangbinaiagent.security;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import java.util.*;
public final class ToolAccess {
 private ToolAccess(){}
 public static boolean allowed(String name,UserContext.User user){
  return user.admin()||Set.of("generatePDF","doTerminate","searchKnowledgeBase").contains(name)
   ||name.endsWith("_currentTime")||name.endsWith("_listKnowledgeDocuments")||name.endsWith("_readKnowledgeDocument");
 }
 public static ToolCallback[] forUser(ToolCallback[] tools,UserContext.User user){
  return Arrays.stream(tools).filter(t->allowed(t.getToolDefinition().name(),user)).map(tool->new ToolCallback(){
   public ToolDefinition getToolDefinition(){return tool.getToolDefinition();}
   public ToolMetadata getToolMetadata(){return tool.getToolMetadata();}
   public String call(String input){return UserContext.call(user,()->tool.call(input));}
   public String call(String input,ToolContext context){return UserContext.call(user,()->tool.call(input,context));}
  }).toArray(ToolCallback[]::new);
 }
}

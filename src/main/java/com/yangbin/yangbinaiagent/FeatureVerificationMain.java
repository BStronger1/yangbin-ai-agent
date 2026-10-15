package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.rag.KnowledgeService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.util.*;

/** Explicit operator CLI: checks real local dependencies without paid model calls. */
public class FeatureVerificationMain {
    public static void main(String[] args) throws Exception {
        try(var context=new SpringApplicationBuilder(YangbinAiAgentApplication.class).web(WebApplicationType.NONE).profiles("prod").run(args)) {
            ToolCallback[] tools=context.getBean("allTools",ToolCallback[].class);
            var result=new LinkedHashMap<String,Object>();
            var docs=context.getBean(KnowledgeService.class).search("夫妻之间如何有效沟通");
            require(!docs.isEmpty(),"RAG returned no evidence");
            result.put("rag_sources",docs.stream().map(d->d.getMetadata().get("filename")).toList());
            require(tool(tools,"searchKnowledgeBase").call("{\"query\":\"夫妻之间如何有效沟通\"}").contains("资料"),"RAG tool failed");
            require(tool(tools,"currentTime").call("{\"timezone\":\"Asia/Shanghai\"}").contains("+08:00"),"MCP clock failed");
            require(tool(tools,"listKnowledgeDocuments").call("{}").contains(".md"),"MCP listing failed");
            String name="恋爱常见问题和回答 - 已婚篇.md";
            require(tool(tools,"readKnowledgeDocument").call(new ObjectMapper().writeValueAsString(Map.of("filename",name))).contains("已婚"),"MCP reading failed");
            require(tool(tools,"executeTerminalCommand").call("{\"command\":\"echo terminal-linux-ok\"}").contains("terminal-linux-ok"),"Terminal failed");
            result.put("mcp",true);result.put("terminal",true);result.put("rag",true);result.put("paid_model_calls",0);
            String report=new ObjectMapper().writeValueAsString(result);
            Files.writeString(Path.of("logs/feature-verification.json"),report);
            System.out.println("FEATURE_VERIFICATION "+report);
        }
    }
    private static ToolCallback tool(ToolCallback[] tools,String suffix) {
        return Arrays.stream(tools).filter(t->t.getToolDefinition().name().endsWith(suffix)).findFirst().orElseThrow();
    }
    private static void require(boolean success,String message){if(!success)throw new IllegalStateException(message);}
}

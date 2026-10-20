package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.rag.KnowledgeService;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/ai")
public class CapabilitiesController {
    private final ObjectProvider<KnowledgeService> knowledge;
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcp;
    private final ToolCallback[] tools;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.yangbin.yangbinaiagent.coding.CodingRuntime runtime;
    public CapabilitiesController(ObjectProvider<KnowledgeService> knowledge,ObjectProvider<SyncMcpToolCallbackProvider> mcp,
                                  @Qualifier("allTools") ToolCallback[] tools) {
        this.knowledge=knowledge;this.mcp=mcp;this.tools=tools;
    }
    @GetMapping("/capabilities")
    public Map<String,Object> capabilities() {
        var user=com.yangbin.yangbinaiagent.security.UserContext.current();
        var names=Arrays.stream(com.yangbin.yangbinaiagent.security.ToolAccess.forUser(tools,user)).map(t->t.getToolDefinition().name()).toList();
        var mcpNames=mcp.getIfAvailable()==null?List.of():Arrays.stream(com.yangbin.yangbinaiagent.security.ToolAccess.forUser(mcp.getObject().getToolCallbacks(),user)).map(t->t.getToolDefinition().name()).toList();
        return Map.of("rag",knowledge.getIfAvailable()!=null,"mcpTools",mcpNames,"terminal",names.contains("executeTerminalCommand")&&runtime!=null&&Boolean.TRUE.equals(runtime.shellAvailability().get("available")),"tools",names);
    }
    @GetMapping("/rag/search")
    public List<Map<String,Object>> search(@RequestParam String query) {
        if(knowledge.getIfAvailable()==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"RAG disabled");
        return knowledge.getObject().search(query).stream().map(d->Map.<String,Object>of(
            "source",d.getMetadata().getOrDefault("filename","unknown"),"content",d.getText(),"score",d.getScore()==null?0:d.getScore())).toList();
    }
}

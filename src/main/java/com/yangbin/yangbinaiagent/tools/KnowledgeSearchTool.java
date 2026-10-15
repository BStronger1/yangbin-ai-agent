package com.yangbin.yangbinaiagent.tools;
import com.yangbin.yangbinaiagent.rag.KnowledgeService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
public class KnowledgeSearchTool {
    private final KnowledgeService knowledge;
    public KnowledgeSearchTool(KnowledgeService knowledge){this.knowledge=knowledge;}
    @Tool(description="Search the local relationship knowledge base using semantic retrieval; returns source filenames and evidence")
    public String searchKnowledgeBase(@ToolParam(description="Question to retrieve relevant evidence for") String query) {
        return knowledge.context(query);
    }
}

package com.yangbin.yangbinaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@ConditionalOnProperty(name="app.rag.enabled",havingValue="true")
public class KnowledgeService {
    private final VectorStore store;
    public KnowledgeService(@Qualifier("loveAppVectorStore") VectorStore store) { this.store=store; }
    public List<Document> search(String query) {
        if(query==null || query.isBlank() || query.length()>4000) throw new IllegalArgumentException("Query must contain 1-4000 characters");
        return store.similaritySearch(SearchRequest.builder().query(query).topK(3).similarityThreshold(0.25).build());
    }
    public String context(String query) {
        var documents=search(query);
        if(documents.isEmpty()) return "知识库未找到相关资料。请明确说明这一点，不要虚构知识库证据。";
        StringBuilder result=new StringBuilder();
        for(int i=0;i<documents.size();i++) {
            var d=documents.get(i);
            result.append("[资料").append(i+1).append("] ").append(d.getMetadata().get("filename"))
                    .append("\n").append(d.getText()).append("\n\n");
        }
        return result.toString();
    }
}

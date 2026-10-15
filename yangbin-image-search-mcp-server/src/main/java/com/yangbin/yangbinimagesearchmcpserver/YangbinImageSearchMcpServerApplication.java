package com.yangbin.yangbinimagesearchmcpserver;
import com.yangbin.yangbinimagesearchmcpserver.tools.ImageSearchTool;
import com.yangbin.yangbinimagesearchmcpserver.tools.KnowledgeTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.util.ArrayList;

@SpringBootApplication
public class YangbinImageSearchMcpServerApplication {
    public static void main(String[] args) { SpringApplication.run(YangbinImageSearchMcpServerApplication.class,args); }
    @Bean
    public ToolCallbackProvider serverTools(KnowledgeTools knowledge,ImageSearchTool images,
            @Value("${PEXELS_API_KEY:}") String pexelsKey) {
        var tools=new ArrayList<Object>();tools.add(knowledge);
        if(!pexelsKey.isBlank()) tools.add(images);
        return MethodToolCallbackProvider.builder().toolObjects(tools.toArray()).build();
    }
}

package com.yangbin.yangbinaiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

/**
 * 闆嗕腑鐨勫伐鍏锋敞鍐岀被
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Value("${app.tools.extended-enabled:false}")
    private boolean extendedToolsEnabled;

    @Value("${app.tools.terminal-enabled:false}")
    private boolean terminalEnabled;

    @Bean
    public ToolCallback[] allTools() {
        List<Object> tools = new ArrayList<>();
        if (searchApiKey != null && !searchApiKey.isBlank()) tools.add(new WebSearchTool(searchApiKey));
        tools.add(new PDFGenerationTool());
        tools.add(new TerminateTool());
        if (extendedToolsEnabled) {
            tools.add(new FileOperationTool());
            tools.add(new WebScrapingTool());
            tools.add(new ResourceDownloadTool());
        }
        if (terminalEnabled) tools.add(new TerminalOperationTool());
        return ToolCallbacks.from(tools.toArray());
    }
}

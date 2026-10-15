package com.yangbin.yangbinimagesearchmcpserver.tools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.*;

@Component
public class KnowledgeTools {
    private final Path root;
    public KnowledgeTools(@Value("${app.knowledge-dir:knowledge}") String root) { this.root=Path.of(root).toAbsolutePath().normalize(); }
    @Tool(description="List available Markdown knowledge documents on the local MCP server")
    public String listKnowledgeDocuments() throws Exception {
        try(var paths=Files.list(root)) {
            return String.join("\n",paths.filter(p->Files.isRegularFile(p) && !Files.isSymbolicLink(p) && p.toString().endsWith(".md"))
                    .map(p->p.getFileName().toString()).sorted().toList());
        }
    }
    @Tool(description="Read one named knowledge document from the MCP server. Use listKnowledgeDocuments first.")
    public String readKnowledgeDocument(@ToolParam(description="Exact Markdown filename from the document list") String filename) throws Exception {
        if(filename==null || filename.contains("/") || filename.contains("\\") || !filename.endsWith(".md")) throw new IllegalArgumentException("Use a plain Markdown filename");
        Path target=root.resolve(filename).normalize();
        if(!target.getParent().equals(root) || Files.isSymbolicLink(target) || !Files.isRegularFile(target)) throw new IllegalArgumentException("Unknown document");
        if(Files.size(target)>1024*1024) throw new IllegalArgumentException("Document too large");
        String text=Files.readString(target);
        return filename+"\n"+text.substring(0,Math.min(text.length(),12000));
    }
    @Tool(description="Get the current date and time in an IANA timezone")
    public String currentTime(@ToolParam(description="IANA timezone, for example Asia/Shanghai") String timezone) {
        return ZonedDateTime.now(ZoneId.of(timezone)).toString();
    }
}

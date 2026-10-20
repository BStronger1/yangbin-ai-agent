package com.yangbin.yangbinaiagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Only executes through the verified Linux sandbox runner; never falls back to a host shell. */
public class TerminalOperationTool {
    private final Path directory;
    private final int timeoutSeconds;
    private final int outputLimit;
    public TerminalOperationTool() { this(Path.of("tmp/terminal"),30,32768); }
    public TerminalOperationTool(Path directory,int timeoutSeconds,int outputLimit) {
        this.directory=directory.toAbsolutePath(); this.timeoutSeconds=Math.max(1,Math.min(timeoutSeconds,120));this.outputLimit=Math.max(128,Math.min(outputLimit,65536));
    }
    @Tool(description="Run a verification command in a verified Linux sandbox with network disabled and a read-only working-directory snapshot. Credentials and protected files are excluded. Temporary changes are discarded. If isolation is unavailable the command is rejected; no host-shell fallback exists.")
    public String executeTerminalCommand(@ToolParam(description="Verification command to execute") String command) {
        if(command==null || command.isBlank() || command.length()>8000) return "Error: command must contain 1-8000 characters";
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) return "Error: terminal unavailable; verified Linux isolation is required";
        Path runner=Path.of(System.getProperty("app.terminal.sandbox-entry",System.getenv().getOrDefault("CODING_SANDBOX_ENTRY","coding-agent/src/sandbox.mjs"))).toAbsolutePath().normalize();
        if(!Files.isRegularFile(runner))return "Error: terminal unavailable; sandbox runner is not installed";
        Process process=null;
        try {
            Files.createDirectories(directory);
            String node=System.getProperty("app.terminal.node",System.getenv().getOrDefault("CODING_NODE","node"));
            var builder=new ProcessBuilder(node,runner.toString()).redirectErrorStream(true);
            builder.environment().clear();builder.environment().put("PATH","/usr/bin:/bin");builder.environment().put("LANG","C.UTF-8");
            if(System.getenv("CODING_BWRAP")!=null)builder.environment().put("CODING_BWRAP",System.getenv("CODING_BWRAP"));
            process=builder.start();
            process.getOutputStream().write(new ObjectMapper().writeValueAsBytes(Map.of("workspace",directory.toString(),"command",command,"timeoutSeconds",timeoutSeconds)));
            process.getOutputStream().close();
            Process current=process;
            StringBuilder output=new StringBuilder();
            Thread reader=Thread.ofVirtual().start(() -> {
                try(var stream=new InputStreamReader(current.getInputStream(),StandardCharsets.UTF_8)) {
                    char[] buffer=new char[2048];int count;
                    while((count=stream.read(buffer))!=-1) synchronized(output) {
                        int remaining=outputLimit-output.length();
                        if(remaining>0) output.append(buffer,0,Math.min(remaining,count));
                    }
                } catch(Exception ignored) { }
            });
            boolean finished=process.waitFor(timeoutSeconds+8L,TimeUnit.SECONDS);
            if(!finished) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor(2,TimeUnit.SECONDS); }
            reader.join(1000);
            synchronized(output) {
                return (finished&&process.exitValue()==0?"":"Error: ")+output+(output.length()>=outputLimit?"\n[Output truncated]":"")+(finished?"":"\n[Isolated command timed out]");
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();return "Error: terminal command interrupted";
        } catch(Exception e) { return "Error: isolated terminal unavailable"; }
        finally {
            if(process!=null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly(); }
        }
    }
}

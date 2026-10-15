package com.yangbin.yangbinaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Runs with the service account's OS permissions; the working directory is not a sandbox. */
public class TerminalOperationTool {
    private final Path directory;
    private final int timeoutSeconds;
    private final int outputLimit;
    public TerminalOperationTool() { this(Path.of("tmp/terminal"),30,32768); }
    public TerminalOperationTool(Path directory,int timeoutSeconds,int outputLimit) {
        this.directory=directory.toAbsolutePath(); this.timeoutSeconds=timeoutSeconds;this.outputLimit=outputLimit;
    }
    @Tool(description="Execute a shell command as the service user. Starts in the application terminal working directory; timeout and output limits apply. Do not access credentials or execute destructive operations unless explicitly requested by the user.")
    public String executeTerminalCommand(@ToolParam(description="Shell command to execute") String command) {
        if(command==null || command.isBlank() || command.length()>16000) return "Error: command must contain 1-16000 characters";
        Process process=null;
        try {
            Files.createDirectories(directory);
            var builder=new ProcessBuilder(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? new String[]{"cmd.exe","/c",command} : new String[]{"/bin/sh","-c",command});
            builder.directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().keySet().removeIf(k -> k.toUpperCase(Locale.ROOT).matches(".*(KEY|TOKEN|PASSWORD|SECRET).*"));
            process=builder.start(); process.getOutputStream().close();
            Process current=process;
            StringBuilder output=new StringBuilder();
            Thread reader=Thread.ofVirtual().start(() -> {
                try(var stream=new InputStreamReader(current.getInputStream(),StandardCharsets.UTF_8)) {
                    char[] buffer=new char[2048];int count;
                    while((count=stream.read(buffer))!=-1) {
                        synchronized(output) {
                            int remaining=outputLimit-output.length();
                            if(remaining>0) output.append(buffer,0,Math.min(remaining,count));
                        }
                    }
                } catch(Exception ignored) { }
            });
            boolean finished=process.waitFor(timeoutSeconds,TimeUnit.SECONDS);
            if(!finished) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor(2,TimeUnit.SECONDS); }
            reader.join(1000);
            process.getInputStream().close();
            synchronized(output) {
                return output.toString()+(output.length()>=outputLimit?"\n[Output truncated]":"")
                        +(finished?"\nExit code: "+process.exitValue():"\n[Timed out after "+timeoutSeconds+" seconds]");
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();return "Error: terminal command interrupted";
        } catch(Exception e) { return "Error executing command: "+e.getMessage(); }
        finally {
            if(process!=null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly(); }
        }
    }
}

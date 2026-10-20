package com.yangbin.yangbinaiagent;
import com.yangbin.yangbinaiagent.tools.TerminalOperationTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.assertThat;
class TerminalOperationToolUnitTest {
    @TempDir Path directory;
    @Test void missingIsolationNeverFallsBackToHostShell(){
        String previous=System.getProperty("app.terminal.sandbox-entry");
        System.setProperty("app.terminal.sandbox-entry",directory.resolve("missing-sandbox.mjs").toString());
        try {
            assertThat(new TerminalOperationTool(directory,3,1024).executeTerminalCommand("echo unsafe > marker.txt")).contains("Error:","unavailable");
            assertThat(Files.exists(directory.resolve("marker.txt"))).isFalse();
        } finally { if(previous==null)System.clearProperty("app.terminal.sandbox-entry");else System.setProperty("app.terminal.sandbox-entry",previous); }
    }
    @Test void emptyOrOversizeCommandIsRejectedBeforeProcessStart(){
        var terminal=new TerminalOperationTool(directory,3,128);
        assertThat(terminal.executeTerminalCommand("")).contains("1-8000");
        assertThat(terminal.executeTerminalCommand("x".repeat(8001))).contains("1-8000");
    }
}

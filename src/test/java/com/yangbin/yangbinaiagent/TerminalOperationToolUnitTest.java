package com.yangbin.yangbinaiagent;
import com.yangbin.yangbinaiagent.tools.TerminalOperationTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
class TerminalOperationToolUnitTest {
    @TempDir Path directory;
    @Test void commandOutputAndExitCodeAreReported(){
        assertThat(new TerminalOperationTool(directory,3,1024).executeTerminalCommand("echo terminal-ok")).contains("terminal-ok","Exit code: 0");
    }
    @Test void longRunningCommandIsStopped(){
        String command=System.getProperty("os.name").toLowerCase().contains("win")?"ping -n 20 127.0.0.1 > nul":"sleep 20";
        assertTimeoutPreemptively(Duration.ofSeconds(6),()->assertThat(new TerminalOperationTool(directory,1,128).executeTerminalCommand(command)).contains("Timed out"));
    }
    @Test void outputIsBounded(){
        String result=new TerminalOperationTool(directory,3,128).executeTerminalCommand("echo "+"x".repeat(2000));
        assertThat(result).contains("Output truncated");assertThat(result.length()).isLessThan(256);
    }
}

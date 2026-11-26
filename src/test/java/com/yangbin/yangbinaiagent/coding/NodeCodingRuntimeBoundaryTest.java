package com.yangbin.yangbinaiagent.coding;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class NodeCodingRuntimeBoundaryTest {
    @TempDir Path root;
    @Test void independentFixSwitchesReachTheIsolatedWorkerProcess() throws Exception {
        Path entry=root.resolve("src/main.mjs");Files.createDirectories(entry.getParent());Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(entry,"""
            import readline from 'node:readline';
            if(process.argv.includes('--check')){console.log('{"ok":true}');process.exit(0);}
            console.log('{"type":"ready"}');
            readline.createInterface({input:process.stdin}).on('line',line=>{
              const request=JSON.parse(line);
              console.log(JSON.stringify({type:'event',event:{type:'fixes',receipts:process.env.CODING_MUTATION_RECEIPTS_FIX,lifecycle:process.env.CODING_TEST_LIFECYCLE_FIX}}));
              console.log(JSON.stringify({type:'response',id:request.sessionId,ok:true}));
            });
            """);
        var runtime=new NodeCodingRuntime(new ObjectMapper(),true,System.getProperty("coding.test.node","node"),entry.toString(),"fixture-key","http://127.0.0.1","fixture","custom",30,"","","");
        for(var pair:List.of(List.of("on","off"),List.of("off","on"))){
            ReflectionTestUtils.setField(runtime,"mutationReceiptsFix",pair.get(0));ReflectionTestUtils.setField(runtime,"testLifecycleFix",pair.get(1));
            List<Map<String,Object>> events=new ArrayList<>();
            runtime.run(new CodingRuntime.Request("fixture",root,root.resolve("session"),"fixture","allow",false),events::add,new LinkedBlockingQueue<>());
            assertThat(events).hasSize(1);assertThat(events.getFirst()).containsEntry("receipts",pair.get(0)).containsEntry("lifecycle",pair.get(1));
        }
        ReflectionTestUtils.setField(runtime,"mutationReceiptsFix","invalid");
        assertThatThrownBy(()->runtime.run(new CodingRuntime.Request("fixture",root,root.resolve("session"),"fixture","allow",false),event->{},new LinkedBlockingQueue<>())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readsExactBoundedLinesAndEof() throws Exception {
        var reader=new StringReader("abc\n\nend");
        assertThat(NodeCodingRuntime.readBoundedLine(reader,3)).isEqualTo("abc");
        assertThat(NodeCodingRuntime.readBoundedLine(reader,3)).isEmpty();
        assertThat(NodeCodingRuntime.readBoundedLine(reader,3)).isEqualTo("end");
        assertThat(NodeCodingRuntime.readBoundedLine(reader,3)).isNull();
    }
    @Test void refusesOversizedUnterminatedStreamWithoutConsumingRemainder() {
        int[] consumed={0};
        Reader infinite=new Reader(){
            public int read(char[] chars,int off,int len){chars[off]='x';consumed[0]++;return 1;}
            public void close(){}
        };
        assertThatThrownBy(()->NodeCodingRuntime.readBoundedLine(infinite,128)).isInstanceOf(IOException.class).hasMessageContaining("too large");
        assertThat(consumed[0]).isEqualTo(129);
    }
}

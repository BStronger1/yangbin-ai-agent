package com.yangbin.yangbinaiagent.coding;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NodeCodingRuntimeBoundaryTest {
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

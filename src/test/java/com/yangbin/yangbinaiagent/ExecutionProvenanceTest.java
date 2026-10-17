package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ExecutionProvenanceTest {
    @Test void sortedFingerprintIgnoresObjectOrderButPreservesArrayOrder() throws Exception {
        var audit=new ExecutionProvenance("abcdef1","openai","model-1","");
        var json=new ObjectMapper();
        Map<String,Object> first=new LinkedHashMap<>();first.put("z",1);first.put("a",List.of(1,2));
        Map<String,Object> second=new LinkedHashMap<>();second.put("a",List.of(1,2));second.put("z",1);
        var a=audit.capture("model",1,json,first);
        assertThat(a.inputHash()).isEqualTo(audit.capture("model",1,json,second).inputHash());
        second.put("a",List.of(2,1));
        assertThat(a.inputHash()).isNotEqualTo(audit.capture("model",1,json,second).inputHash());
        assertThat(a.configuredModel()).isEqualTo("model-1");
    }
    @Test void dashscopeAndNonModelStepsUseTheirActualConfigurationScope() throws Exception {
        var audit=new ExecutionProvenance("abcdef1","dashscope","wrong-openai-default","qwen-plus");
        var json=new ObjectMapper();
        assertThat(audit.capture("planning",1,json,Map.of()).configuredModel()).isEqualTo("qwen-plus");
        assertThat(audit.capture("evidence",1,json,Map.of()).configuredModel()).isNull();
        assertThat(audit.capture("evidence",1,json,Map.of()).modelProvider()).isNull();
    }
    @Test void missingOrInvalidMetadataDoesNotPretendToBeKnown() throws Exception {
        var audit=new ExecutionProvenance("not-a-commit","unknown","unused","unused");
        var result=audit.capture("model",1,new ObjectMapper(),Map.of());
        assertThat(result.codeRevision()).isNull();
        assertThat(result.configuredModel()).isNull();
        assertThat(result.modelProvider()).isNull();
    }
}

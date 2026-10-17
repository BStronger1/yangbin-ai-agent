package com.yangbin.yangbinaiagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Records the configured execution context before a logical attempt; never reads keys or endpoints. */
@Component
public class ExecutionProvenance {
    public record Attempt(String schemaVersion,String stepId,int attempt,String reservedAt,
                          String codeRevision,String modelProvider,String configuredModel,
                          String inputHash,String inputHashFormat) {}
    private final String revision,provider,model;

    public ExecutionProvenance(@Value("${app.build.revision:}") String revision,
                               @Value("${app.model.provider:dashscope}") String provider,
                               @Value("${app.model.name:}") String openAiModel,
                               @Value("${spring.ai.dashscope.chat.options.model:}") String dashscopeModel) {
        this.revision=revision!=null&&revision.matches("[a-fA-F0-9]{7,40}(?:-dirty)?")?revision:null;
        this.provider=Set.of("openai","dashscope").contains(provider==null?"":provider)?provider:null;
        this.model=label("openai".equals(this.provider)?openAiModel:"dashscope".equals(this.provider)?dashscopeModel:null);
    }
    public static ExecutionProvenance unknown() {return new ExecutionProvenance("","","","");}
    public Attempt capture(String step,int attempt,ObjectMapper json,Object input) throws IOException {
        boolean modelStep=Set.of("planning","model","repair").contains(step);
        try {
            byte[] canonical=json.writeValueAsBytes(sorted(json.valueToTree(input)));
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
            return new Attempt("execution-attempt-v1",step,attempt,Instant.now().toString(),
                    revision,modelStep?provider:null,modelStep?model:null,hash,"jackson-sorted-json-v1");
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static String label(String value) {
        return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")?value:null;
    }
    private static Object sorted(JsonNode node) {
        if(node.isObject()) {
            Map<String,Object> result=new TreeMap<>();
            node.fields().forEachRemaining(entry->result.put(entry.getKey(),sorted(entry.getValue())));
            return result;
        }
        if(node.isArray()) {List<Object> result=new ArrayList<>();node.forEach(item->result.add(sorted(item)));return result;}
        return node;
    }
}

package com.yangbin.yangbinaiagent.coding;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yangbin.yangbinaiagent.models.ModelProfileService;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

/** Narrow transport boundary; credentials never enter persisted requests or public DTOs. */
public interface CodingRuntime {
    record Availability(boolean available, String reason, String model) {}
    record Request(String sessionId, Path workspace, Path sessionDir, String prompt, String approvalMode, boolean allowShell,
                   @JsonIgnore ModelProfileService.Resolved model) {
        public Request(String sessionId,Path workspace,Path sessionDir,String prompt,String approvalMode,boolean allowShell){this(sessionId,workspace,sessionDir,prompt,approvalMode,allowShell,null);}
    }
    Availability availability();
    default Availability availability(ModelProfileService.Resolved selected){return availability();}
    void run(Request request, Consumer<Map<String,Object>> events, BlockingQueue<Map<String,Object>> commands) throws Exception;
}

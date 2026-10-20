package com.yangbin.yangbinaiagent.coding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.yangbin.yangbinaiagent.models.ModelProfileService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
public class NodeCodingRuntime implements CodingRuntime {
    private final ObjectMapper json;
    private final String node, key, baseUrl, model, provider;
    @Value("${app.coding.api:${CODING_MODEL_API:openai-completions}}") private String systemApi="openai-completions";
    @Value("${app.coding.context-window:${CODING_CONTEXT_WINDOW:64000}}") private int contextWindow=64000;
    @Value("${app.governance.output-tokens:4096}") private int outputTokens=4096;
    private final Path entry;
    private final boolean enabled;
    private final long timeoutSeconds;
    private volatile Map<String,Object> sandbox=Map.of("available",false,"reason","Verified isolation has not been checked");
    private volatile long checkedAt; private volatile String runtimeProblem="";
    public NodeCodingRuntime(ObjectMapper json,
        @Value("${app.coding.enabled:${CODING_ENABLED:false}}") boolean enabled,
        @Value("${app.coding.node:${CODING_NODE:node}}") String node,
        @Value("${app.coding.entry:${CODING_ENTRY:coding-agent/src/main.mjs}}") String entry,
        @Value("${app.coding.api-key:${CODING_MODEL_API_KEY:}}") String key,
        @Value("${app.coding.base-url:${CODING_MODEL_BASE_URL:}}") String baseUrl,
        @Value("${app.coding.model:${CODING_MODEL_NAME:${CODING_MODEL_MODEL:}}}") String model,
        @Value("${app.coding.provider:${CODING_MODEL_PROVIDER:custom}}") String provider,
        @Value("${app.coding.timeout-seconds:900}") long timeoutSeconds,
        @Value("${app.model.api-key:}") String sharedKey,
        @Value("${app.model.base-url:}") String sharedBaseUrl,
        @Value("${app.model.name:}") String sharedModel) {
        this.json=json;this.enabled=enabled;this.node=node;this.entry=Path.of(entry).toAbsolutePath().normalize();
        this.key=key.isBlank()?sharedKey:key;
        String selectedBase=(baseUrl.isBlank()?sharedBaseUrl:baseUrl).strip();
        this.baseUrl=selectedBase.replaceAll("/+$","");this.model=(model.isBlank()?sharedModel:model).strip();this.provider=provider;this.timeoutSeconds=Math.max(10,Math.min(timeoutSeconds,3600));
    }
    public Availability availability(){return availability(null);}
    public Availability availability(ModelProfileService.Resolved selected) {
        String effectiveKey=selected==null?key:selected.apiKey(),effectiveBase=selected==null?baseUrl:selected.snapshot().baseUrl(),effectiveModel=selected==null?model:selected.snapshot().model();
        String reason=!enabled?"管理员尚未启用 Coding Agent 运行时":!Files.isRegularFile(entry)?"Coding Agent 运行时未安装":effectiveKey.isBlank()||effectiveBase.isBlank()||effectiveModel.isBlank()?"管理员尚未配置 Coding Agent 专用模型":!Files.isDirectory(entry.getParent().getParent().resolve("node_modules"))?"Coding Agent 依赖尚未安装":"";
        if(reason.isEmpty())reason=probeRuntime();
        return new Availability(reason.isEmpty(),reason,effectiveModel);
    }
    public Map<String,Object> shellAvailability(){if(!enabled)return Map.of("available",false,"reason","Coding runtime is disabled");probeRuntime();return sandbox;}
    private synchronized String probeRuntime(){
        long now=System.currentTimeMillis();if(now-checkedAt<30000)return runtimeProblem;
        Process process=null;sandbox=Map.of("available",false,"reason","Verified isolation is unavailable");runtimeProblem="Coding Agent 运行时检查失败，请检查 Node 版本和依赖";
        try{var probe=new ProcessBuilder(node,entry.toString(),"--check").directory(entry.getParent().getParent().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);restrictEnvironment(probe);process=probe.start();
            if(process.waitFor(10,TimeUnit.SECONDS)&&process.exitValue()==0){String output=new String(process.getInputStream().readNBytes(4096),StandardCharsets.UTF_8);var result=json.readTree(output);if(result.path("ok").asBoolean()){runtimeProblem="";if(result.path("sandbox").isObject())sandbox=json.convertValue(result.path("sandbox"),new TypeReference<Map<String,Object>>(){});}}
        }catch(Exception ignored){}finally{if(process!=null)kill(process);checkedAt=now;}return runtimeProblem;
    }
    public void run(Request request,Consumer<Map<String,Object>> sink,BlockingQueue<Map<String,Object>> commands)throws Exception {
        var selected=request.model();if(!availability(selected).available())throw new IllegalStateException("Coding runtime unavailable");
        String activeKey=selected==null?key:selected.apiKey(),activeBase=selected==null?baseUrl:selected.snapshot().baseUrl(),activeModel=selected==null?model:selected.snapshot().model(),activeProvider=selected==null?provider:selected.snapshot().provider(),activeApi=selected==null?systemApi:selected.snapshot().api();
        ProcessBuilder builder=new ProcessBuilder(node,entry.toString()).directory(entry.getParent().getParent().toFile());
        restrictEnvironment(builder);
        builder.environment().put("CODING_GOVERNANCE_REQUIRED","true");
        builder.environment().put("CODING_CONTEXT_WINDOW",Integer.toString(Math.max(4096,Math.min(contextWindow,200000))));
        builder.environment().put("CODING_OUTPUT_TOKENS",Integer.toString(Math.max(1,Math.min(outputTokens,65536))));
        builder.environment().put("CODING_MODEL_API_KEY",activeKey);builder.environment().put("CODING_MODEL_BASE_URL",activeBase);
        builder.environment().put("CODING_MODEL_NAME",activeModel);builder.environment().put("CODING_MODEL_MODEL",activeModel);builder.environment().put("CODING_MODEL_PROVIDER",activeProvider);
        builder.environment().put("CODING_MODEL_API",activeApi);builder.environment().put("CODING_MODEL_ALLOW_PRIVATE",Boolean.toString(selected==null||selected.allowPrivate()));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD); // provider/process errors must not expose secrets
        Process process=builder.start();
        var timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"coding-timeout");t.setDaemon(true);return t;});
        timer.schedule(()->kill(process),timeoutSeconds,TimeUnit.SECONDS);
        try(var writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));var reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
            Thread commandWriter=Thread.ofVirtual().start(()->{
                try{while(process.isAlive()){
                    var command=commands.poll(1,TimeUnit.SECONDS);if(command==null)continue;
                    synchronized(writer){writer.write(json.writeValueAsString(command));writer.newLine();writer.flush();}
                    if("cancel".equals(command.get("type")))timer.schedule(()->kill(process),3,TimeUnit.SECONDS);
                }}catch(Exception ignored){kill(process);}
            });
            boolean started=false,completed=false;
            try{
                String line;
                while((line=readBoundedLine(reader,2_000_000))!=null){
                    Map<String,Object> item=json.readValue(line,new TypeReference<>(){});
                    if("ready".equals(item.get("type"))&&!started){
                        var start=new LinkedHashMap<String,Object>();start.put("id",request.sessionId());start.put("type","start");start.put("sessionId",request.sessionId());start.put("workspace",request.workspace().toString());start.put("sessionDir",request.sessionDir().toString());start.put("prompt",request.prompt());start.put("approvalMode",request.approvalMode());start.put("allowShell",request.allowShell());
                        synchronized(writer){writer.write(json.writeValueAsString(start));writer.newLine();writer.flush();}started=true;
                    }else if("event".equals(item.get("type"))&&item.get("event") instanceof Map<?,?> raw){
                        @SuppressWarnings("unchecked") Map<String,Object> event=(Map<String,Object>)raw;
                        // Literal configured key is redacted even if a malicious model echoes it.
                        String safe=json.writeValueAsString(event);if(!activeKey.isEmpty())safe=com.yangbin.yangbinaiagent.models.SecretRedactor.redact(safe,activeKey);
                        sink.accept(json.readValue(safe,new TypeReference<>(){}));
                    }else if("response".equals(item.get("type"))&&Objects.equals(item.get("id"),request.sessionId())){
                        if(!Boolean.TRUE.equals(item.get("ok")))throw new IOException("Coding runtime failed");completed=true;break;
                    }
                }
                if(!completed)throw new IOException("Coding runtime interrupted");
            }finally{commandWriter.interrupt();}
        }finally{timer.shutdownNow();kill(process);}
    }
    /** Stop before allocating an unbounded child-process line. Reader buffering stays bounded too. */
    static String readBoundedLine(Reader reader,int limit)throws IOException {
        if(limit<1)throw new IllegalArgumentException("Invalid line limit");
        StringBuilder line=new StringBuilder(Math.min(limit,4096));
        int value;
        while((value=reader.read())!=-1){
            if(value=='\n')return line.toString();
            if(line.length()>=limit)throw new IOException("Runtime event too large");
            line.append((char)value);
        }
        return line.isEmpty()?null:line.toString();
    }
    private static void restrictEnvironment(ProcessBuilder builder){Map<String,String> inherited=new HashMap<>(builder.environment());builder.environment().clear();for(String name:List.of("PATH","Path","SystemRoot","WINDIR","TEMP","TMP","LANG","CODING_BWRAP"))if(inherited.containsKey(name))builder.environment().put(name,inherited.get(name));}
    private static void kill(Process process){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
}

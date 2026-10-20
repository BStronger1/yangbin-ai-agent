package com.yangbin.yangbinaiagent.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Single-instance, durable admission ledger. No prompts, keys, endpoints or exception bodies are stored. */
@Service
public class ModelGovernance {
    public record Limits(int accountConcurrency,int globalConcurrency,int dailyRequests,long dailyTokens,int outputTokens) {}
    public record Lease(String traceId,String owner,String day) {}
    public record Event(String traceId,String requestId,String provider,String model,String status,long startedAt,long durationMs,
                        long reservedTokens,Long inputTokens,Long outputTokens,long chargedTokens,String costStatus) {}
    public record Summary(String day,String timezone,Limits limits,int active,int globalActive,long requests,
                          long knownTokens,long unknownUsageRequests,long budgetChargedTokens,long remainingTokens,
                          long failures,String costStatus,String scope) {}
    public static class Day { public List<Event> events=new ArrayList<>(); }
    public static class Denied extends IllegalStateException { public Denied(String message){super(message);} }
    private static class Circuit { int failures; long until; boolean probe; }
    private record Pending(Lease lease,Long input,Long output,String status) {}
    private final ObjectMapper json; private final Path root; private final Clock clock; private final Limits limits;
    private final Map<String,Day> days=new HashMap<>(); private final Map<String,Circuit> circuits=new HashMap<>();
    private final Map<String,Pending> pending=new LinkedHashMap<>();
    private final Set<String> active=new HashSet<>(); private final Map<String,Integer> owners=new HashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public ModelGovernance(ObjectMapper json,@Value("${app.governance.dir:tmp/governance}") String dir,
      @Value("${app.governance.account-concurrency:2}") int account,@Value("${app.governance.global-concurrency:8}") int global,
      @Value("${app.governance.daily-requests:100}") int requests,@Value("${app.governance.daily-tokens:200000}") long tokens,
      @Value("${app.governance.output-tokens:4096}") int output) { this(json,Path.of(dir),Clock.systemUTC(),new Limits(account,global,requests,tokens,output)); }
    public ModelGovernance(ObjectMapper json,Path root,Clock clock,Limits limits) {
        this.json=json;this.root=root.toAbsolutePath().normalize();this.clock=clock;this.limits=limits;
        if(limits.accountConcurrency()<1||limits.globalConcurrency()<1||limits.dailyRequests()<1||limits.dailyRequests()>100000||limits.dailyTokens()<1||limits.outputTokens()<1)throw new IllegalArgumentException("Invalid model governance limits");
        try { if(Files.isSymbolicLink(this.root))throw new IllegalStateException();Files.createDirectories(this.root); }
        catch(Exception e){throw new IllegalStateException("模型用量目录不可用");}
    }
    public synchronized Lease reserve(String provider,String model,long reservedTokens) {
        flushPending();
        String owner=UserContext.current().id(),day=date();Day ledger=load(owner,day);
        if(reservedTokens<1||reservedTokens>limits.dailyTokens())throw new Denied("单次模型请求超过每日 Token 预算，请缩短上下文");
        if(active.size()>=limits.globalConcurrency()||owners.getOrDefault(owner,0)>=limits.accountConcurrency())throw new Denied("模型请求并发已满，请稍后重试");
        if(ledger.events.size()>=limits.dailyRequests())throw new Denied("今日模型请求次数已用完");
        long charged=ledger.events.stream().mapToLong(Event::chargedTokens).sum();
        if(charged>limits.dailyTokens()-reservedTokens)throw new Denied("今日模型 Token 预算不足；未知用量会保守保留预留额度");
        String safeProvider=label(provider),safeModel=label(model);Circuit circuit=circuits.computeIfAbsent(owner+":"+safeProvider+":"+safeModel,k->new Circuit());
        long now=clock.millis();if(circuit.until>now||circuit.probe)throw new Denied("模型连续失败，熔断等待中，请稍后重试");
        if(circuit.failures>=3)circuit.probe=true;
        String id=UUID.randomUUID().toString();Event e=new Event(id,requestId(),safeProvider,safeModel,"RUNNING",now,0,reservedTokens,null,null,reservedTokens,"UNKNOWN");
        ledger.events.add(e);try{save(owner,day,ledger);}catch(RuntimeException failure){ledger.events.remove(e);circuit.probe=false;throw failure;}
        active.add(id);owners.merge(owner,1,Integer::sum);return new Lease(id,owner,day);
    }
    public synchronized void finish(Lease lease,Long inputTokens,Long outputTokens,String status) {
        if(lease==null||!active.contains(lease.traceId()))return;
        if(!Set.of("SUCCEEDED","FAILED","CANCELLED","UNKNOWN").contains(status))throw new IllegalArgumentException("Invalid model completion status");
        Pending deferred=pending.get(lease.traceId());if(deferred!=null){inputTokens=deferred.input();outputTokens=deferred.output();status=deferred.status();}
        Day ledger=load(lease.owner(),lease.day());
        for(int i=0;i<ledger.events.size();i++) {Event old=ledger.events.get(i);if(!old.traceId().equals(lease.traceId()))continue;
            Long input=valid(inputTokens),output=valid(outputTokens);boolean known=input!=null&&output!=null;
            // A BYOK server can omit or underreport usage; quota never refunds the conservative reservation.
            long charge=known?Math.max(old.reservedTokens(),Math.addExact(input,output)):old.reservedTokens();
            Event done=new Event(old.traceId(),old.requestId(),old.provider(),old.model(),status,old.startedAt(),Math.max(0,clock.millis()-old.startedAt()),old.reservedTokens(),input,output,charge,"UNKNOWN");
            ledger.events.set(i,done);try{save(lease.owner(),lease.day(),ledger);}catch(RuntimeException failure){ledger.events.set(i,old);pending.putIfAbsent(lease.traceId(),new Pending(lease,inputTokens,outputTokens,status));throw failure;}
            pending.remove(lease.traceId());active.remove(lease.traceId());owners.compute(lease.owner(),(k,v)->v==null||v<=1?null:v-1);
            Circuit c=circuits.computeIfAbsent(lease.owner()+":"+old.provider()+":"+old.model(),k->new Circuit());c.probe=false;
            if(status.equals("SUCCEEDED")){c.failures=0;c.until=0;}else if(status.equals("FAILED")||status.equals("UNKNOWN")){if(++c.failures>=3)c.until=clock.millis()+30000;}
            return;
        }
    }
    private void flushPending(){for(Pending item:List.copyOf(pending.values()))finish(item.lease(),item.input(),item.output(),item.status());}
    public synchronized Summary summary() {
        flushPending();
        String owner=UserContext.current().id();Day d=load(owner,date());long known=0,unknown=0,charged=0,failed=0;
        for(Event e:d.events){charged+=e.chargedTokens();if(e.inputTokens()!=null&&e.outputTokens()!=null)known+=e.inputTokens()+e.outputTokens();else unknown++;if(Set.of("FAILED","UNKNOWN","CANCELLED").contains(e.status()))failed++;}
        return new Summary(date(),"UTC",limits,owners.getOrDefault(owner,0),UserContext.current().admin()?active.size():-1,d.events.size(),known,unknown,charged,Math.max(0,limits.dailyTokens()-charged),failed,"UNKNOWN","当前服务实例受控模型调用；预算扣除至少为预留估算，与实际已知Token分别统计；不含外部嵌入/PPT服务，费用未配置价格");
    }
    public synchronized List<Event> events(){flushPending();var items=new ArrayList<>(load(UserContext.current().id(),date()).events);Collections.reverse(items);return items.stream().limit(100).toList();}
    private static String requestId(){String value=org.slf4j.MDC.get("requestId");return value!=null&&value.matches("[a-f0-9]{32}")?value:null;}
    private String date(){return LocalDate.now(clock).toString();}
    private static Long valid(Long n){return n==null||n<0||n>1000000000000L?null:n;}
    private static String label(String value){return value!=null&&value.matches("[a-zA-Z0-9._:/-]{1,120}")&&!value.contains("://")?value:"custom";}
    private Day load(String owner,String day){
        if(!UserContext.validId(owner)||!day.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException("Invalid ledger identity");
        String key=owner+":"+day;if(days.containsKey(key))return days.get(key);
        Path file=file(owner,day);try { if(Files.isSymbolicLink(file.getParent())||Files.isSymbolicLink(file))throw new IllegalStateException();Day d=Files.exists(file)?json.readValue(file.toFile(),Day.class):new Day();
            boolean changed=false;for(int i=0;i<d.events.size();i++){Event e=d.events.get(i);if(e.status().equals("RUNNING")){d.events.set(i,new Event(e.traceId(),e.requestId(),e.provider(),e.model(),"UNKNOWN",e.startedAt(),0,e.reservedTokens(),null,null,e.reservedTokens(),"UNKNOWN"));changed=true;}}
            if(changed)save(owner,day,d);days.put(key,d);return d;
        }catch(Exception e){throw new IllegalStateException("模型用量账本不可读取，请恢复账本后重试");}
    }
    private Path file(String owner,String day){return root.resolve(owner).resolve(day+".json");}
    private void save(String owner,String day,Day d){Path f=file(owner,day),temp=null;try{if(Files.isSymbolicLink(f.getParent())||Files.isSymbolicLink(f))throw new IllegalStateException();Files.createDirectories(f.getParent());temp=Files.createTempFile(f.getParent(),".usage-",".tmp");json.writeValue(temp.toFile(),d);try{Files.move(temp,f,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,f,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw new IllegalStateException("模型用量账本不可写入，已停止新调用");}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}}

    public ChatModel govern(ChatModel delegate,String provider,String model){return new ChatModel(){
        public ChatOptions getDefaultOptions(){return delegate.getDefaultOptions();}
        public ChatResponse call(Prompt prompt){return controlled(delegate,limited(prompt,delegate.getDefaultOptions()),provider,model);}
        public Flux<ChatResponse> stream(Prompt prompt){UserContext.User captured=currentOrNull();String capturedRequest=requestId();return Flux.deferContextual(context->{
            UserContext.User owner=context.getOrDefault(UserContext.User.class,captured);if(owner==null)return Flux.error(new IllegalStateException("Missing authenticated user context"));
            String candidate=context.getOrDefault("requestId",capturedRequest);String request=candidate!=null&&candidate.matches("[a-f0-9]{32}")?candidate:null;
            return UserContext.call(owner,()->{
            Prompt limited=limited(prompt,delegate.getDefaultOptions());
            // Tool loops need synchronous tool execution between governed model calls. They return a final event, not token chunks.
            if(internalTools(limited)){String previous=org.slf4j.MDC.get("requestId");try{setRequestId(request);return Flux.just(controlled(delegate,limited,provider,model));}finally{setRequestId(previous);}}
            Lease lease;String previous=org.slf4j.MDC.get("requestId");try{setRequestId(request);lease=reserve(provider,model,reservation(limited));}finally{setRequestId(previous);}AtomicReference<Long[]> tokens=new AtomicReference<>(new Long[]{null,null});AtomicBoolean done=new AtomicBoolean();
            Flux<ChatResponse> source;try{source=delegate.stream(limited);}catch(RuntimeException e){finish(lease,null,null,"FAILED");throw safeModelFailure();}
            return source.doOnNext(response->{Long[] next=usage(response);if(next[0]!=null&&next[1]!=null)tokens.set(next);})
             .doOnComplete(()->{if(done.compareAndSet(false,true)){Long[] u=tokens.get();finish(lease,u[0],u[1],"SUCCEEDED");}})
             .doOnError(e->{if(done.compareAndSet(false,true))finish(lease,null,null,"FAILED");})
             .doFinally(signal->{if(done.compareAndSet(false,true))finish(lease,null,null,"CANCELLED");})
             .onErrorMap(error->safeModelFailure());
        });});}
    };}
    private static UserContext.User currentOrNull(){try{return UserContext.current();}catch(IllegalStateException missing){return null;}}
    private static IllegalStateException safeModelFailure(){return new IllegalStateException("模型调用未完成或账本未能保存，请查看调用状态；不会自动重试");}
    private static void setRequestId(String id){if(id==null)org.slf4j.MDC.remove("requestId");else org.slf4j.MDC.put("requestId",id);}
    private ChatResponse once(ChatModel delegate,Prompt prompt,String provider,String model){Lease lease=reserve(provider,model,reservation(prompt));try{ChatResponse response=delegate.call(prompt);Long[] usage=usage(response);finish(lease,usage[0],usage[1],"SUCCEEDED");return response;}catch(RuntimeException e){finish(lease,null,null,"FAILED");throw safeModelFailure();}}
    private static boolean internalTools(Prompt prompt){if(!(prompt.getOptions() instanceof ToolCallingChatOptions t))return false;return !Boolean.FALSE.equals(t.getInternalToolExecutionEnabled())&&((t.getToolCallbacks()!=null&&!t.getToolCallbacks().isEmpty())||(t.getToolNames()!=null&&!t.getToolNames().isEmpty()));}
    private ChatResponse controlled(ChatModel delegate,Prompt prompt,String provider,String model){
        if(!internalTools(prompt))return once(delegate,prompt,provider,model);
        ToolCallingChatOptions options=prompt.getOptions().copy();options.setInternalToolExecutionEnabled(false);
        var manager=org.springframework.ai.model.tool.ToolCallingManager.builder().build();Prompt turn=new Prompt(prompt.getInstructions(),options);
        for(int step=0;step<20;step++){
            if(Thread.currentThread().isInterrupted())throw new Denied("模型工具任务已取消");
            ChatResponse response=once(delegate,turn,provider,model);if(!response.hasToolCalls())return response;
            var result=manager.executeToolCalls(turn,response);if(result.returnDirect())return new ChatResponse(org.springframework.ai.model.tool.ToolExecutionResult.buildGenerations(result),response.getMetadata());
            turn=new Prompt(result.conversationHistory(),options);
        }
        throw new Denied("模型工具任务达到20轮上限，请缩小任务后继续");
    }
    private static <T>T choose(T preferred,T fallback){return preferred==null?fallback:preferred;}
    private Prompt limited(Prompt prompt,ChatOptions defaults){ChatOptions options=prompt.getOptions()!=null?prompt.getOptions():defaults;
        Integer requested=options==null?null:options.getMaxTokens();if(requested==null&&defaults!=null)requested=defaults.getMaxTokens();
        var b=ToolCallingChatOptions.builder().maxTokens(requested==null?limits.outputTokens():Math.min(limits.outputTokens(),Math.max(1,requested)));
        if(options!=null){b.model(choose(options.getModel(),defaults==null?null:defaults.getModel())).temperature(choose(options.getTemperature(),defaults==null?null:defaults.getTemperature())).topP(choose(options.getTopP(),defaults==null?null:defaults.getTopP())).topK(choose(options.getTopK(),defaults==null?null:defaults.getTopK())).frequencyPenalty(choose(options.getFrequencyPenalty(),defaults==null?null:defaults.getFrequencyPenalty())).presencePenalty(choose(options.getPresencePenalty(),defaults==null?null:defaults.getPresencePenalty())).stopSequences(choose(options.getStopSequences(),defaults==null?null:defaults.getStopSequences()));}
        ToolCallingChatOptions tools=options instanceof ToolCallingChatOptions t?t:defaults instanceof ToolCallingChatOptions t?t:null;
        if(tools!=null){if(tools.getToolCallbacks()!=null)b.toolCallbacks(tools.getToolCallbacks());if(tools.getToolNames()!=null)b.toolNames(tools.getToolNames());if(tools.getToolContext()!=null)b.toolContext(tools.getToolContext());b.internalToolExecutionEnabled(tools.getInternalToolExecutionEnabled());}
        return new Prompt(prompt.getInstructions(),b.build());}
    private long reservation(Prompt prompt){long input=1024;for(var m:prompt.getInstructions()){input+=Objects.toString(m.getText(),"").getBytes(StandardCharsets.UTF_8).length+128;
            if(m instanceof org.springframework.ai.chat.messages.AssistantMessage a)for(var call:a.getToolCalls())input+=Objects.toString(call.arguments(),"").getBytes(StandardCharsets.UTF_8).length+128;
            if(m instanceof org.springframework.ai.chat.messages.ToolResponseMessage t)for(var response:t.getResponses())input+=Objects.toString(response.responseData(),"").getBytes(StandardCharsets.UTF_8).length+128;
        }
        if(prompt.getOptions() instanceof ToolCallingChatOptions t&&t.getToolCallbacks()!=null)for(var tool:t.getToolCallbacks())input+=tool.getToolDefinition().inputSchema().getBytes(StandardCharsets.UTF_8).length+512;
        return input+prompt.getOptions().getMaxTokens();}
    private static Long[] usage(ChatResponse r){if(r==null||r.getMetadata()==null)return new Long[]{null,null};Usage u=r.getMetadata().getUsage();if(u==null||u instanceof EmptyUsage)return new Long[]{null,null};return new Long[]{u.getPromptTokens()==null?null:u.getPromptTokens().longValue(),u.getCompletionTokens()==null?null:u.getCompletionTokens().longValue()};}
}

package com.yangbin.yangbinaiagent.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.planner.PlannerService;
import com.yangbin.yangbinaiagent.planner.PlanningEvidence;
import com.yangbin.yangbinaiagent.security.UserContext;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Single-process durable task execution. External requests are not exactly-once operations. */
@Service
public class TaskService implements AutoCloseable {
    public record Step(String id,String title,String status,int attempts,String startedAt,String finishedAt,
                       long durationMs,String summary,String error) {}
    public record Event(long sequence,String time,String stepId,String type,String message) {}
    public record Metrics(long durationMs,int modelCalls,int toolCalls,int failedAttempts,Long promptTokens,Long completionTokens) {}
    public record TaskView(String id,String title,String prompt,String status,String createdAt,String updatedAt,
                           PlannerService.Requirements requirements,List<Step> steps,List<Event> events,
                           String planId,Integer planVersion,String pdfUrl,String error,Metrics metrics,
                           PlanningEvidence.Snapshot evidence,List<Map<String,Object>> sources,PlannerService.Itinerary itinerary) {}
    /** On-disk state includes identity and idempotency metadata; these are never returned as API fields. */
    public static class State {
        public String id,requestId,owner,role,prompt,status,createdAt,updatedAt,error,pdfUrl;
        public PlannerService.Requirements requirements;
        public List<Step> steps=new ArrayList<>();
        public List<Event> events=new ArrayList<>();
        public List<Map<String,Object>> sources=new ArrayList<>();
        public PlanningEvidence.Snapshot evidence;
        public PlannerService.Itinerary itinerary;
        public PlannerService.Plan plan;
        public long sequence;
        public int modelCalls,toolCalls,failedAttempts;
    }
    private static final List<String> IDS=List.of("requirements","knowledge","evidence","model","save","pdf");
    private static final List<String> TITLES=List.of("核对出行要求","检索个人知识库","查询天气与地点","生成行程方案","校验并保存方案","生成 PDF");
    private static final Set<String> ACTIVE=Set.of("QUEUED","RUNNING","CANCELLING");
    private final ObjectMapper json;
    private final TaskWorkflow workflow;
    private final Path root;
    private final ExecutorService executor;
    private final Map<String,State> tasks=new HashMap<>();
    private volatile boolean closing;
    private boolean sealed;

    @Autowired
    public TaskService(ObjectMapper json,TaskWorkflow workflow,@Value("${app.tasks.dir:tmp/tasks}") String dir) throws IOException {
        this(json,workflow,dir,new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{
            Thread thread=new Thread(r,"travel-task");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy()));
    }
    public TaskService(ObjectMapper json,TaskWorkflow workflow,String dir,ExecutorService executor) throws IOException {
        this.json=json;this.workflow=workflow;this.root=Path.of(dir);this.executor=executor;
        Files.createDirectories(root);
        try(var files=Files.list(root)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).toList()) {
                State state=json.readValue(file.toFile(),State.class);
                validateStored(state,file);
                tasks.put(state.id,state);
                if(ACTIVE.contains(state.status)||("INTERRUPTED".equals(state.status)&&state.steps.stream().anyMatch(step->"RUNNING".equals(step.status())))) {
                    boolean cancelled="CANCELLING".equals(state.status);
                    state.status=cancelled?"CANCELLED":"INTERRUPTED";
                    state.error=cancelled?null:"服务已重启，请继续任务；未确认完成的模型请求可能再次计费";
                    for(int i=0;i<state.steps.size();i++) {
                        Step step=state.steps.get(i);
                        if("RUNNING".equals(step.status()))state.steps.set(i,finish(step,cancelled?"CANCELLED":"PENDING",step.summary(),null));
                        else if(cancelled&&"PENDING".equals(step.status()))state.steps.set(i,finish(step,"CANCELLED",step.summary(),null));
                    }
                    event(state,null,"RECOVERED",cancelled?"已恢复取消状态":"检测到未完成任务，等待手动继续");persist(state);
                }
            }
        }
    }
    private void validateStored(State s,Path file) throws IOException {
        if(s.id==null||!file.getFileName().toString().equals(s.id+".json")||!UserContext.validId(s.owner)
                ||s.requirements==null||s.steps==null||s.steps.size()!=IDS.size())throw new IOException("Invalid task state");
        try { new UserContext.User(s.owner,s.role);UUID.fromString(s.id); }
        catch(RuntimeException error) { throw new IOException("Invalid task identity"); }
        for(int i=0;i<IDS.size();i++)if(!IDS.get(i).equals(s.steps.get(i).id()))throw new IOException("Invalid task steps");
    }
    public synchronized TaskView create(String requestId,String prompt,PlannerService.Requirements requirements) throws IOException {
        ensureOpen();
        if(requestId==null||!requestId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))throw new IllegalArgumentException("请求编号须为 UUID");
        requestId=requestId.toLowerCase(Locale.ROOT);
        if(prompt==null||prompt.isBlank()||prompt.length()>4000)throw new IllegalArgumentException("请填写 1–4000 字的出行目标");
        PlannerService.validate(requirements);
        var user=UserContext.current();
        for(State previous:tasks.values())if(user.id().equals(previous.owner)&&requestId.equals(previous.requestId)) {
            if(!prompt.equals(previous.prompt)||!requirements.equals(previous.requirements))throw new IllegalStateException("相同请求编号的内容不同，请创建新任务");
            return view(previous);
        }
        if(tasks.values().stream().filter(s->s.owner.equals(user.id())&&ACTIVE.contains(s.status)).count()>=5)throw new IllegalStateException("最多同时执行 5 个任务，请等待已有任务完成");
        State state=new State();state.id=UUID.randomUUID().toString();state.requestId=requestId;state.owner=user.id();state.role=user.role();
        state.prompt=prompt;state.requirements=requirements;state.status="QUEUED";state.createdAt=now();state.updatedAt=state.createdAt;
        for(int i=0;i<IDS.size();i++)state.steps.add(new Step(IDS.get(i),TITLES.get(i),"PENDING",0,null,null,0,null,null));
        event(state,null,"CREATED","已创建出行任务，等待执行");persist(state);tasks.put(state.id,state);enqueue(state);
        return view(state);
    }
    public synchronized TaskView get(String id) { return view(owned(id)); }
    public synchronized List<TaskView> list() {
        String owner=UserContext.current().id();
        return tasks.values().stream().filter(t->t.owner.equals(owner)).sorted(Comparator.comparing((State t)->t.createdAt).reversed()).limit(100).map(this::view).toList();
    }
    public synchronized TaskView retry(String id) throws IOException {
        ensureOpen();State state=owned(id);
        if(!Set.of("FAILED","INTERRUPTED").contains(state.status))throw new IllegalStateException("只有失败或中断任务可以继续");
        if(tasks.values().stream().filter(s->s.owner.equals(state.owner)&&ACTIVE.contains(s.status)).count()>=5)throw new IllegalStateException("最多同时执行 5 个任务，请稍后继续");
        state.status="QUEUED";state.error=null;
        for(int i=0;i<state.steps.size();i++) {
            Step step=state.steps.get(i);
            if(!"SUCCEEDED".equals(step.status()))state.steps.set(i,new Step(step.id(),step.title(),"PENDING",step.attempts(),null,null,step.durationMs(),step.summary(),null));
        }
        event(state,null,"RETRY","继续未完成步骤；已经保存的结果将被复用");persist(state);enqueue(state);return view(state);
    }
    public synchronized TaskView cancel(String id) throws IOException {
        State state=owned(id);
        if("SUCCEEDED".equals(state.status))throw new IllegalStateException("已完成任务不能取消");
        if("CANCELLED".equals(state.status)||"CANCELLING".equals(state.status))return view(state);
        state.status="RUNNING".equals(state.status)?"CANCELLING":"CANCELLED";state.error=null;
        if("CANCELLED".equals(state.status))cancelPending(state);
        event(state,null,"CANCEL_REQUESTED","CANCELLING".equals(state.status)?"正在等待当前调用结束，不会启动后续步骤":"任务已取消");persist(state);return view(state);
    }
    private void ensureOpen() { if(closing)throw new IllegalStateException("服务正在重启，请稍后继续"); }
    private State owned(String id) {
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("任务不存在");
        State state=tasks.get(id);if(state==null||!state.owner.equals(UserContext.current().id()))throw new IllegalArgumentException("任务不存在");return state;
    }
    private void enqueue(State state) throws IOException {
        try { executor.execute(()->run(state.id)); }
        catch(RejectedExecutionException rejected) {
            state.status="FAILED";state.error="执行队列已满，请稍后继续";
            event(state,null,"QUEUE_FULL",state.error);persist(state);
        }
    }
    private void run(String id) {
        State state;
        synchronized(this) {
            state=tasks.get(id);if(state==null||!"QUEUED".equals(state.status)||closing)return;
            state.status="RUNNING";
            try {event(state,null,"STARTED","开始执行任务");persist(state);}
            catch(IOException failure) {state.status="FAILED";state.error="无法保存任务进度，请检查存储后继续";return;}
        }
        try(var scope=UserContext.open(new UserContext.User(state.owner,state.role))) {
            for(int index=0;index<IDS.size();index++) {
                synchronized(this) {
                    if(stop(state))return;
                    Step step=state.steps.get(index);if("SUCCEEDED".equals(step.status()))continue;
                    state.steps.set(index,new Step(step.id(),step.title(),"RUNNING",step.attempts()+1,now(),null,step.durationMs(),step.summary(),null));
                    if("model".equals(step.id()))state.modelCalls++;
                    if(Set.of("knowledge","evidence","pdf").contains(step.id()))state.toolCalls++;
                    event(state,step.id(),"STEP_STARTED",step.title());persist(state);
                }
                Object result;
                try { result=execute(state,index); }
                catch(Exception failure) {
                    synchronized(this) {
                        if(sealed)return;
                        Step step=state.steps.get(index);String message=safeFailure(index);
                        state.steps.set(index,finish(step,"FAILED",step.summary(),message));state.failedAttempts++;
                        if(!stop(state)) {state.status="FAILED";state.error=message;event(state,step.id(),"STEP_FAILED",message);persist(state);}
                    }
                    return;
                }
                synchronized(this) {
                    if(sealed)return;
                    apply(state,index,result);
                    Step step=state.steps.get(index);
                    state.steps.set(index,finish(step,"SUCCEEDED",summary(state,index),null));
                    event(state,step.id(),"STEP_SUCCEEDED",state.steps.get(index).summary());persist(state);
                    if(stop(state))return;
                }
            }
            synchronized(this) {
                if(!stop(state)){state.status="SUCCEEDED";state.error=null;event(state,null,"SUCCEEDED","行程方案与 PDF 已完成");persist(state);}
            }
        } catch(EvidenceUnavailable handled) {
            // The failed upstream status and checkpoint were saved by apply().
        } catch(Exception storageFailure) {
            synchronized(this) {
                if(sealed)return;
                if(!Set.of("CANCELLED","INTERRUPTED").contains(state.status)) {
                    state.status=closing?"INTERRUPTED":"FAILED";state.error="任务进度保存异常，请检查存储后继续";
                    try {event(state,null,"STORAGE_ERROR",state.error);persist(state);}catch(IOException ignored) { /* No further external steps are executed. */ }
                }
            }
        }
    }
    private Object execute(State s,int index) throws Exception {
        return switch(index) {
            case 0 -> { workflow.validate(s.requirements);yield null; }
            case 1 -> workflow.sources(s.requirements);
            case 2 -> workflow.evidence(s.requirements);
            case 3 -> workflow.draft(s.requirements,s.prompt,s.sources,s.evidence);
            case 4 -> workflow.save(s.id,s.requirements,s.itinerary,s.sources,s.evidence);
            case 5 -> workflow.export(s.plan);
            default -> throw new IllegalStateException("Invalid step");
        };
    }
    @SuppressWarnings("unchecked")
    private void apply(State s,int index,Object result) throws IOException {
        switch(index) {
            case 1 -> s.sources=List.copyOf((List<Map<String,Object>>)result);
            case 2 -> {
                s.evidence=(PlanningEvidence.Snapshot)result;
                // Save the failure snapshot as evidence, while keeping the step retryable.
                if(s.evidence==null||"unavailable".equals(s.evidence.weatherStatus())||"unavailable".equals(s.evidence.placesStatus())) {
                    Step step=s.steps.get(index);s.steps.set(index,finish(step,"FAILED","已保存本次查询状态",safeFailure(index)));s.failedAttempts++;
                    s.status="CANCELLING".equals(s.status)?"CANCELLED":"FAILED";s.error="CANCELLED".equals(s.status)?null:safeFailure(index);
                    if("CANCELLED".equals(s.status))cancelPending(s);
                    event(s,step.id(),"STEP_FAILED",safeFailure(index));persist(s);
                    throw new EvidenceUnavailable();
                }
            }
            case 3 -> s.itinerary=(PlannerService.Itinerary)result;
            case 4 -> {s.plan=(PlannerService.Plan)result;s.itinerary=s.plan.itinerary();}
            case 5 -> s.pdfUrl=(String)result;
        }
    }
    private static class EvidenceUnavailable extends IOException {}
    private String summary(State s,int index) {
        return switch(index) {
            case 0 -> s.requirements.city()+" · "+s.requirements.date()+" · 两人预算 "+s.requirements.budget()+" 元";
            case 1 -> s.sources.isEmpty()?"未检索到个人资料，将使用已填写要求和实时依据":"已检索 "+s.sources.size()+" 条知识库片段";
            case 2 -> "天气："+switch(s.evidence.weatherStatus()){case "available"->"可用";case "out_of_range"->"超出预报范围，按无天气依据降级";case "disabled"->"未启用，按无天气依据降级";default->"不可用";}+"；地点候选 "+s.evidence.places().size()+" 个";
            case 3 -> "模型已生成行程草稿，并通过时间、预算和地点校验";
            case 4 -> "方案已保存为第 "+s.plan.version()+" 版";
            case 5 -> "PDF 已生成，可下载";
            default -> "完成";
        };
    }
    private String safeFailure(int index) {
        return switch(index) {
            case 0 -> "出行要求校验失败，请创建符合约束的新任务";
            case 1 -> "知识库检索暂不可用，请稍后重试";
            case 2 -> "天气或地点查询暂不可用，已暂停；请稍后继续任务";
            case 3 -> "模型调用失败或方案未通过约束校验，请重试本步骤";
            case 4 -> "方案校验或保存失败，请检查服务状态后继续";
            case 5 -> "PDF 导出失败，请稍后继续；已完成方案会被复用";
            default -> "任务暂未完成，请稍后继续";
        };
    }
    /** Called only under this monitor, before another external step can be started. */
    private boolean stop(State s) throws IOException {
        if("CANCELLING".equals(s.status)||"CANCELLED".equals(s.status)) {
            s.status="CANCELLED";s.error=null;cancelPending(s);event(s,null,"CANCELLED","任务已取消，已完成结果仍保留");persist(s);return true;
        }
        if(closing||"INTERRUPTED".equals(s.status)) {
            s.status="INTERRUPTED";s.error="服务已停止，请继续未完成步骤";event(s,null,"INTERRUPTED",s.error);persist(s);return true;
        }
        return !"RUNNING".equals(s.status);
    }
    private void cancelPending(State s) {
        for(int i=0;i<s.steps.size();i++) {Step step=s.steps.get(i);if("PENDING".equals(step.status()))s.steps.set(i,finish(step,"CANCELLED",step.summary(),null));}
    }
    private static Step finish(Step s,String status,String summary,String error) {
        long duration=s.durationMs();if(s.startedAt()!=null&&s.finishedAt()==null)duration+=Math.max(0,Instant.now().toEpochMilli()-Instant.parse(s.startedAt()).toEpochMilli());
        return new Step(s.id(),s.title(),status,s.attempts(),s.startedAt(),now(),duration,summary,error);
    }
    private void event(State state,String step,String type,String message) {
        state.updatedAt=now();state.events.add(new Event(++state.sequence,state.updatedAt,step,type,message));
        if(state.events.size()>1000)state.events.remove(0);
    }
    private void persist(State state) throws IOException {
        Path temporary=Files.createTempFile(root,"task-",".tmp");
        try {json.writeValue(temporary.toFile(),state);Files.move(temporary,root.resolve(state.id+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(temporary);}
    }
    private TaskView view(State s) {
        long duration=s.steps.stream().mapToLong(step->step.durationMs()+(("RUNNING".equals(step.status())&&step.startedAt()!=null)?Math.max(0,Instant.now().toEpochMilli()-Instant.parse(step.startedAt()).toEpochMilli()):0)).sum();
        return new TaskView(s.id,s.requirements.city()+"出行 · "+s.requirements.date(),s.prompt,s.status,s.createdAt,s.updatedAt,s.requirements,
                List.copyOf(s.steps),List.copyOf(s.events),s.plan==null?null:s.plan.id(),s.plan==null?null:s.plan.version(),s.pdfUrl,s.error,
                new Metrics(duration,s.modelCalls,s.toolCalls,s.failedAttempts,null,null),s.evidence,List.copyOf(s.sources),s.itinerary);
    }
    private static String now() { return Instant.now().toString(); }
    @Override @PreDestroy
    public void close() {
        closing=true;executor.shutdown();
        try {if(!executor.awaitTermination(5,TimeUnit.SECONDS))executor.shutdownNow();}
        catch(InterruptedException interrupted) {executor.shutdownNow();Thread.currentThread().interrupt();}
        synchronized(this) {
            sealed=true;
            for(State s:tasks.values())if(ACTIVE.contains(s.status)||"INTERRUPTED".equals(s.status)) {
                boolean cancelled="CANCELLING".equals(s.status);s.status=cancelled?"CANCELLED":"INTERRUPTED";
                s.error=cancelled?null:"服务已停止，请继续未完成步骤";
                for(int index=0;index<s.steps.size();index++){Step step=s.steps.get(index);if("RUNNING".equals(step.status()))s.steps.set(index,finish(step,cancelled?"CANCELLED":"PENDING",step.summary(),null));}
                if(cancelled)cancelPending(s);
                event(s,null,cancelled?"CANCELLED":"INTERRUPTED",cancelled?"已取消任务":s.error);
                try{persist(s);}catch(IOException ignored) { /* Startup will recover the last durable RUNNING/QUEUED state. */ }
            }
        }
    }
}

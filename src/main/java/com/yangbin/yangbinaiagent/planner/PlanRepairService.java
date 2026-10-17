package com.yangbin.yangbinaiagent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import com.yangbin.yangbinaiagent.security.UserContext;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** One durable logical model attempt per plan, followed by independently retryable publication. */
@Service
public class PlanRepairService implements AutoCloseable {
    public record View(String planId,Integer sourceVersion,Integer targetVersion,String status,int attempts,
                       String message,String createdAt,String updatedAt,PlannerService.Itinerary beforeItinerary,
                       PlannerService.Itinerary afterItinerary,PlanQualityService.Report beforeQuality,PlanQualityService.Report afterQuality) {}
    public static class State {
        public String planId,owner,status,message,createdAt,updatedAt;
        public int attempts;
        public ExecutionProvenance.Attempt executionAttempt;
        public Integer targetVersion;
        public PlannerService.Plan original;
        public PlannerService.Itinerary candidate;
        public PlanQualityService.Report beforeQuality,afterQuality;
    }
    private static final String UUID_PATTERN="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final Set<String> STATUSES=Set.of("RUNNING","PUBLISHING","REPAIRED","REJECTED","FAILED","INTERRUPTED","CONFLICT");
    @Autowired private ExecutionProvenance provenance=ExecutionProvenance.unknown();
    private final ObjectMapper json;
    private final PlannerService planner;
    private final Path root;
    private final Map<String,State> repairs=new HashMap<>();
    private final Set<String> publishing=new HashSet<>();
    private boolean closed;

    @Autowired
    public PlanRepairService(ObjectMapper json,PlannerService planner,@Value("${app.repairs.dir:tmp/repairs}") String dir) throws IOException {
        this.json=json;this.planner=planner;this.root=Path.of(dir);Files.createDirectories(root);
        try(var files=Files.list(root)) {
            for(Path file:files.filter(path->path.getFileName().toString().matches(UUID_PATTERN+"\\.json")).toList()) {
                State state=json.readValue(file.toFile(),State.class);validateStored(state,file);
                repairs.put(state.planId,state);
                if("RUNNING".equals(state.status)) {
                    state.status="INTERRUPTED";state.message="上次纠错调用中断，次数已保留；不会自动再次请求模型。";state.updatedAt=now();persist(state);
                }
            }
        }
    }
    private void validateStored(State s,Path file) throws IOException {
        if(s==null||s.planId==null||!s.planId.matches(UUID_PATTERN)||!file.getFileName().toString().equals(s.planId+".json")
                ||!UserContext.validId(s.owner)||s.attempts!=1||!STATUSES.contains(s.status)||s.original==null
                ||!s.planId.equals(s.original.id())||!s.owner.equals(s.original.owner())||s.beforeQuality==null)
            throw new IOException("Invalid repair state");
        if(Set.of("PUBLISHING","REPAIRED").contains(s.status)&&(s.candidate==null||s.afterQuality==null||s.targetVersion==null
                ||s.targetVersion!=s.original.version()+1))throw new IOException("Incomplete repair publication");
    }

    public View get(String planId) throws Exception {
        var user=UserContext.current();var latest=ownedLatest(planId,user);
        State state;
        synchronized(this) {state=ownedState(planId,user);if(state==null)return notStarted(latest);}
        return publish(state,user);
    }
    public View repair(String planId,int expectedVersion) throws Exception {
        var user=UserContext.current();var latest=ownedLatest(planId,user);
        State state;boolean invoke=false;
        synchronized(this) {
            ensureOpen();state=ownedState(planId,user);
            if(state==null) {
                // Serialize the durable reservation with normal revision publication. The model call stays outside both locks.
                synchronized(planner) {
                    latest=ownedLatest(planId,user);
                    if(expectedVersion<1||latest.version()!=expectedVersion)throw new IllegalStateException("方案已有新版本，请刷新后再决定是否纠错");
                    if(latest.evidence()==null)throw new IllegalStateException("此方案没有来源快照，无法执行来源约束纠错");
                    var before=PlanQualityService.assess(latest.requirements(),latest.itinerary(),latest.evidence());
                    if(warnings(before)==0)throw new IllegalStateException("当前规则没有发现告警，无需启动纠错");
                    if(repairs.values().stream().anyMatch(s->s.owner.equals(user.id())&&Set.of("RUNNING","PUBLISHING").contains(s.status)))
                        throw new IllegalStateException("每个账号同时只能纠错一个方案，请等待当前纠错或发布完成");
                    state=new State();state.planId=planId;state.owner=user.id();state.original=latest;state.beforeQuality=before;
                    state.status="RUNNING";state.attempts=1;state.createdAt=now();state.updatedAt=state.createdAt;
                    state.executionAttempt=provenance.capture("repair",1,json,Map.of("original",latest,"beforeQuality",before));
                    state.message="唯一一次逻辑纠错调用已预留，正在生成候选；不会自动循环。";
                    // Insert before IO so even an ambiguous persistence failure cannot open a second call in this process.
                    repairs.put(planId,state);
                    try {persist(state);invoke=true;}
                    catch(IOException storageFailure) {
                        state.status="FAILED";state.updatedAt=now();state.message="无法持久化纠错调用记录，本次没有请求模型；请检查服务存储。";
                        try {persist(state);}catch(IOException ignored) { /* No model call occurred; a durable RUNNING marker, if present, stays consumed. */ }
                    }
                }
            }
        }
        if(invoke)return generate(state,user);
        return publish(state,user);
    }

    private View generate(State state,UserContext.User user) {
        synchronized(this) {if(closed||!"RUNNING".equals(state.status))return view(state);}
        PlannerService.Itinerary raw=null;
        try(var scope=UserContext.open(user)) {
            raw=planner.repairDraft(state.original,state.beforeQuality);
            synchronized(this) {if(closed||!"RUNNING".equals(state.status))return view(state);}
            var candidate=planner.prepareRepair(state.original,raw);
            var after=PlanQualityService.assess(state.original.requirements(),candidate,state.original.evidence());
            synchronized(this) {
                if(closed||!"RUNNING".equals(state.status))return view(state);
                state.candidate=candidate;state.afterQuality=after;state.updatedAt=now();
                if(!improves(state.beforeQuality,after)) {
                    state.status="REJECTED";state.message="候选未满足全部准入条件：告警数量须严格减少、不能新增告警类型、地点匹配数不能下降；原方案保持不变。";
                    persistRetainingConsumption(state);return view(state);
                }
                state.status="PUBLISHING";state.targetVersion=state.original.version()+1;
                state.message="候选已通过规则改进检查，等待发布新版本；原方案和原任务文件保持不变。";
                try {persist(state);}catch(IOException storageFailure) {
                    state.message="候选检查已完成，但发布检查点暂未保存；可刷新重试保存和发布，不会再次请求模型。";
                    return view(state);
                }
            }
            return publish(state,user);
        } catch(Exception failure) {
            synchronized(this) {
                if(closed||!"RUNNING".equals(state.status))return view(state);
                state.candidate=raw;
                if(raw!=null)state.afterQuality=PlanQualityService.assess(state.original.requirements(),raw,state.original.evidence());
                state.status="FAILED";state.updatedAt=now();state.message="纠错调用未完成，或候选未通过结构与约束校验；该方案唯一一次纠错机会已使用，原方案保持不变。";
                persistRetainingConsumption(state);return view(state);
            }
        }
    }

    private View publish(State state,UserContext.User user) {
        synchronized(this) {
            state=repairs.getOrDefault(state.planId,state);
            if(closed||!"PUBLISHING".equals(state.status)||publishing.contains(state.planId))return view(state);
            // This also repairs a previous checkpoint-write failure. Never publish an unrecorded candidate.
            try {persist(state);}catch(IOException storageFailure) {
                state.message="发布检查点暂未保存，请稍后刷新；不会再次请求模型。";return view(state);
            }
            publishing.add(state.planId);
        }
        try(var scope=UserContext.open(user)) {
            PlannerService.Plan published=planner.publishRepair(state.original,state.candidate,state.afterQuality);
            synchronized(this) {
                if(closed)return view(state);
                State completed=copy(state);completed.targetVersion=published.version();completed.status="REPAIRED";completed.updatedAt=now();
                completed.message="已发布纠错新版本；原方案、原任务和原 PDF 未修改。规则改进不等于全文事实已核验。";
                try {persist(completed);repairs.put(state.planId,completed);return view(completed);}
                catch(IOException storageFailure) {
                    // The durable state stays PUBLISHING. The publisher can find and reuse the identical target version.
                    state.message="新版本发布结果待确认，请刷新恢复；只核对或发布同一候选，不会再次请求模型。";return view(state);
                }
            }
        } catch(IllegalStateException versionConflict) {
            synchronized(this) {
                if(closed)return view(state);
                State conflict=copy(state);conflict.status="CONFLICT";conflict.updatedAt=now();
                conflict.message="方案版本已变化，纠错候选未覆盖现有版本；该方案的纠错次数已使用。";
                try {persist(conflict);repairs.put(state.planId,conflict);return view(conflict);}
                catch(IOException storageFailure) {state.message="发布状态暂未保存，请刷新重新核对；不会再次请求模型。";return view(state);}
            }
        } catch(Exception publicationFailure) {
            synchronized(this) {
                if(closed)return view(state);
                state.updatedAt=now();state.message="新版本暂未发布完成，请刷新重试发布；不会再次请求模型。";
                try {persist(state);}catch(IOException ignored) { /* Keep the already durable publication checkpoint. */ }
                return view(state);
            }
        } finally {synchronized(this){publishing.remove(state.planId);}}
    }
    private void persistRetainingConsumption(State state) {
        try {persist(state);}catch(IOException storageFailure) {
            state.message+=" 结果记录暂未保存；持久化调用标记仍保留，重启后不会自动重调模型。";
        }
    }
    private static boolean improves(PlanQualityService.Report before,PlanQualityService.Report after) {
        return warnings(after)<warnings(before)&&warningCodes(before).containsAll(warningCodes(after))&&after.matchedStops()>=before.matchedStops();
    }
    private static long warnings(PlanQualityService.Report report) {return report.findings().stream().filter(f->"warning".equals(f.severity())).count();}
    private static Set<String> warningCodes(PlanQualityService.Report report) {
        Set<String> codes=new HashSet<>();for(var finding:report.findings())if("warning".equals(finding.severity()))codes.add(finding.code());return codes;
    }
    private PlannerService.Plan ownedLatest(String planId,UserContext.User user) throws Exception {
        if(planId==null||!planId.matches(UUID_PATTERN))throw new IllegalArgumentException("方案不存在");
        var plan=planner.latest(planId);
        if(plan==null||!user.id().equals(plan.owner()))throw new IllegalArgumentException("方案不存在");return plan;
    }
    private State ownedState(String planId,UserContext.User user) {
        State state=repairs.get(planId);if(state!=null&&!user.id().equals(state.owner))throw new IllegalArgumentException("方案不存在");return state;
    }
    private void ensureOpen() {if(closed)throw new IllegalStateException("服务正在停止，请稍后查看纠错状态");}
    private View notStarted(PlannerService.Plan plan) {
        var quality=PlanQualityService.assess(plan.requirements(),plan.itinerary(),plan.evidence());
        String message=plan.evidence()==null?"此方案没有来源快照，无法启动来源约束纠错。":warnings(quality)==0?"当前规则没有发现告警，无需纠错。":"可手动发起一次纠错；通过规则准入后发布新版本。";
        return new View(plan.id(),plan.version(),null,"NOT_STARTED",0,message,null,null,plan.itinerary(),null,quality,null);
    }
    private View view(State state) {
        return new View(state.planId,state.original.version(),state.targetVersion,state.status,state.attempts,state.message,state.createdAt,state.updatedAt,
                state.original.itinerary(),state.candidate,state.beforeQuality,state.afterQuality);
    }
    private State copy(State state) {return json.convertValue(state,State.class);}
    private void persist(State state) throws IOException {
        Path temp=Files.createTempFile(root,"repair-",".tmp");
        try {json.writeValue(temp.toFile(),state);Files.move(temp,root.resolve(state.planId+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(temp);}
    }
    private static String now() {return Instant.now().toString();}
    @Override @PreDestroy public synchronized void close() {
        if(closed)return;closed=true;
        for(State state:repairs.values())if("RUNNING".equals(state.status)) {
            state.status="INTERRUPTED";state.message="服务已停止，纠错调用可能已发出；次数保留，不会自动再次请求模型。";state.updatedAt=now();
            try {persist(state);}catch(IOException ignored) { /* Existing RUNNING reservation is recovered as INTERRUPTED on startup. */ }
        }
    }
}

package com.yangbin.yangbinaiagent.coding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.security.UserContext;
import jakarta.annotation.PreDestroy;
import com.yangbin.yangbinaiagent.models.ModelProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** Single-instance owner-scoped store. Projects are private managed directories, never arbitrary host paths. */
@Service
public class CodingService {
    public record Project(String id,String name,String createdAt) {}
    public record ProjectInput(String name) {}
    public record SessionInput(String projectId,String title,String approvalMode) {}
    public record TurnInput(String requestId,String prompt,int revision,String approvalMode,String modelProfileId) { public TurnInput(String requestId,String prompt,int revision,String approvalMode){this(requestId,prompt,revision,approvalMode,null);} }
    public record FileInput(String path,String content) {}
    public record FileInfo(String path,long size) {}
    public record FileContent(String path,String content) {}
    public record Change(String path,String before,String after,String kind) {}
    public static class Turn {
        public String id,prompt,status,createdAt,approvalMode,modelProfileId,requestedModelProfileId;
        public ModelProfileService.Snapshot modelSnapshot;
        public Turn(){}
        Turn(String id,String prompt,String mode){this.id=id;this.prompt=prompt;this.status="RUNNING";this.createdAt=now();this.approvalMode=mode;}
    }
    public static class Session {
        public String id,projectId,title,approvalMode,status,createdAt,updatedAt;
        public int revision;
        public List<Turn> turns=new ArrayList<>();
        public List<Map<String,Object>> events=new ArrayList<>();
    }
    private static final int FILE_LIMIT=262144,MAX_FILES=1000,MAX_SNAPSHOT=8*1024*1024;
    private static final Set<String> ACTIVE=Set.of("RUNNING","WAITING_APPROVAL");
    private static final Set<String> EVENT_TYPES=Set.of("text_delta","tool_start","tool_end","approval_required","approval_resolved","status","error","usage");
    private static final Set<String> DENIED_SEGMENTS=Set.of(".git",".env","node_modules",".ssh",".aws",".codex",".pi");
    private final ObjectMapper json;private final Path root;private final CodingRuntime runtime;private final boolean shellEnabled;private final ModelProfileService modelProfiles;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String,ActiveRun> active=new HashMap<>();
    private static class ActiveRun {final BlockingQueue<Map<String,Object>> commands=new LinkedBlockingQueue<>();}
    public CodingService(ObjectMapper json,CodingRuntime runtime,String dir,boolean shellEnabled)throws IOException {this(json,runtime,dir,shellEnabled,null);}
    @Autowired
    public CodingService(ObjectMapper json,CodingRuntime runtime,@Value("${app.coding.dir:tmp/coding}") String dir,@Value("${app.coding.shell-enabled:${CODING_SHELL_ENABLED:false}}") boolean shellEnabled,ModelProfileService modelProfiles)throws IOException {
        this.json=json;this.runtime=runtime;this.shellEnabled=shellEnabled;this.modelProfiles=modelProfiles;this.root=Path.of(dir).toAbsolutePath().normalize();Files.createDirectories(root);
        // Never replay a model request or approval after a Java process restart.
        try(var owners=Files.list(root)) {for(Path owner:owners.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&UserContext.validId(p.getFileName().toString())).toList()){
            Path sessions=owner.resolve("sessions");if(!Files.isDirectory(sessions,LinkOption.NOFOLLOW_LINKS))continue;
            try(var paths=Files.list(sessions)){for(Path file:paths.filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).toList()){
                Session session=json.readValue(file.toFile(),Session.class);if(ACTIVE.contains(session.status)){finish(session,"INTERRUPTED","服务重启中断了任务，请检查文件后继续；不会自动重试。");write(file,session);}
            }}
        }}
    }
    public Map<String,Object> capabilities(){try{return capabilities(null);}catch(IOException e){throw new IllegalStateException("模型配置暂不可用");}}
    public Map<String,Object> capabilities(String modelProfileId)throws IOException{var selected=modelProfiles==null?null:modelProfiles.resolve(modelProfileId);var a=runtime.availability(selected);return Map.of("available",a.available(),"reason",a.reason(),"model",a.model(),"shellEnabled",shellEnabled&&UserContext.current().admin());}
    public synchronized List<Project> projects()throws IOException {
        Path projects=ownerRoot().resolve("projects");Files.createDirectories(projects);var result=new ArrayList<Project>();
        try(var paths=Files.list(projects)){for(Path p:paths.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList()){Path f=p.resolve("project.json");if(Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS))result.add(json.readValue(f.toFile(),Project.class));}}
        result.sort(Comparator.comparing(Project::createdAt).reversed());return result;
    }
    public synchronized Project createProject(ProjectInput input)throws IOException {
        if(input==null||input.name()==null||input.name().isBlank()||input.name().length()>80)throw new IllegalArgumentException("项目名称需为1–80字");
        if(projects().size()>=30)throw new IllegalStateException("最多保存30个项目");
        var project=new Project(UUID.randomUUID().toString(),input.name().strip(),now());Path folder=ownerRoot().resolve("projects").resolve(project.id());Files.createDirectories(folder.resolve("workspace"));write(folder.resolve("project.json"),project);return project;
    }
    public synchronized List<FileInfo> files(String projectId)throws IOException {
        Path workspace=workspace(projectId);var result=new ArrayList<FileInfo>();
        for(Path p:projectFiles(workspace)){
            String relative=relative(workspace,p);if(!allowedPath(relative))continue;safeFile(workspace,relative);result.add(new FileInfo(relative,Files.size(p)));if(result.size()>MAX_FILES)throw new IllegalStateException("项目文件过多，请缩小工作区");
        }result.sort(Comparator.comparing(FileInfo::path));return result;
    }
    public synchronized FileContent file(String projectId,String name)throws IOException {
        Path file=safeFile(workspace(projectId),name);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("文件不存在");return new FileContent(name,readText(file));
    }
    public synchronized FileContent putFile(String projectId,FileInput input)throws IOException {
        if(input==null||input.content()==null||input.content().getBytes(StandardCharsets.UTF_8).length>FILE_LIMIT||input.content().indexOf('\0')>=0)throw new IllegalArgumentException("仅支持不超过256KiB的文本文件");
        assertProjectIdle(projectId);Path workspace=workspace(projectId),file=safeFile(workspace,input.path());
        if(!Files.exists(file)&&files(projectId).size()>=MAX_FILES)throw new IllegalStateException("项目文件过多");
        Files.createDirectories(file.getParent());safeFile(workspace,input.path());Files.writeString(file,input.content(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);return new FileContent(input.path(),input.content());
    }
    public synchronized List<Session> sessions()throws IOException {
        var result=new ArrayList<Session>();Path folder=ownerRoot().resolve("sessions");Files.createDirectories(folder);
        try(var paths=Files.list(folder)){for(Path p:paths.filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).toList())result.add(json.readValue(p.toFile(),Session.class));}
        result.sort(Comparator.comparing((Session s)->s.updatedAt).reversed());return result;
    }
    public synchronized Session createSession(SessionInput input)throws IOException {
        if(input==null)throw new IllegalArgumentException("缺少会话参数");workspace(input.projectId());String mode=mode(input.approvalMode());
        if(sessions().size()>=100)throw new IllegalStateException("最多保存100个会话");
        var s=new Session();s.id=UUID.randomUUID().toString();s.projectId=input.projectId();s.title=input.title()==null||input.title().isBlank()?"新工作任务":input.title().strip();if(s.title.length()>100)throw new IllegalArgumentException("标题过长");s.approvalMode=mode;s.status="IDLE";s.createdAt=now();s.updatedAt=s.createdAt;save(s);return s;
    }
    public synchronized Session session(String id)throws IOException {Path file=sessionPath(id);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("会话不存在");return json.readValue(file.toFile(),Session.class);}
    public synchronized Session submit(String id,TurnInput input)throws IOException {
        if(input==null||input.prompt()==null||input.prompt().isBlank()||input.prompt().length()>24000)throw new IllegalArgumentException("请输入1–24000字的任务");uuid(input.requestId());String mode=mode(input.approvalMode());Session s=session(id);
        var previous=s.turns.stream().filter(t->t.id.equals(input.requestId())).findFirst();
        if(previous.isPresent()){if(!previous.get().prompt.equals(input.prompt())||!previous.get().approvalMode.equals(mode)||!Objects.equals(Objects.toString(previous.get().requestedModelProfileId,""),Objects.toString(input.modelProfileId(),"")))throw new IllegalStateException("请求ID已用于其他内容");return s;}
        if(s.revision!=input.revision())throw new IllegalStateException("会话已更新，请刷新后发送");
        if(s.turns.size()>=100)throw new IllegalStateException("会话达到100轮上限，请新建任务");
        if(s.events.size()>19000)throw new IllegalStateException("会话事件达到上限，请新建任务");
        if(hasActiveWorker()||sessions().stream().anyMatch(x->ACTIVE.contains(x.status)))throw new IllegalStateException("请等待当前 Coding Agent 任务结束");
        var selected=modelProfiles==null?null:modelProfiles.resolve(input.modelProfileId());
        var availability=runtime.availability(selected);if(!availability.available())throw new IllegalStateException(availability.reason());
        Path workspace=workspace(s.projectId);var baseline=snapshot(workspace);Path state=ownerRoot().resolve("runtime").resolve(s.id);Files.createDirectories(state);write(state.resolve("baseline.json"),baseline);
        s.approvalMode=mode;s.status="RUNNING";s.revision++;var turn=new Turn(input.requestId(),input.prompt(),mode);turn.requestedModelProfileId=input.modelProfileId();turn.modelProfileId=selected==null?ModelProfileService.SYSTEM:selected.snapshot().profileId();turn.modelSnapshot=selected==null?new ModelProfileService.Snapshot("system","系统默认","system","",availability.model(),"system"):selected.snapshot();s.turns.add(turn);s.updatedAt=now();save(s);
        var user=UserContext.current();var run=new ActiveRun();String key=key(id);active.put(key,run);
        var request=new CodingRuntime.Request(id,workspace,state,input.prompt(),mode,shellEnabled&&user.admin(),selected);
        executor.submit(()->{try(var ignored=UserContext.open(user)){execute(id,request,run);} });return s;
    }
    private void execute(String id,CodingRuntime.Request request,ActiveRun run){
        String result="SUCCEEDED",message=null;
        try{runtime.run(request,e->{try{acceptEvent(id,e);}catch(IOException ex){throw new UncheckedIOException(ex);}},run.commands);}
        catch(Exception e){result="FAILED";message="Coding Agent 执行未完成，请检查已产生的文件与事件。不会自动重试。";}
        synchronized(this){try{Session s=session(id);if(ACTIVE.contains(s.status)){finish(s,result,message);save(s);}}catch(IOException ignored){}finally{active.remove(key(id),run);}}
    }
    private synchronized void acceptEvent(String id,Map<String,Object> incoming)throws IOException {
        Session s=session(id);if(!ACTIVE.contains(s.status))return;String type=Objects.toString(incoming.get("type"),"");if(!EVENT_TYPES.contains(type))return;
        if(s.events.size()>=20000)throw new IOException("Event limit exceeded");
        Map<String,Object> event=new LinkedHashMap<>(incoming);event.put("seq",s.events.size()+1);event.put("turnId",s.turns.getLast().id);event.put("createdAt",now());
        if("approval_required".equals(type)) {if(!(event.get("approvalId") instanceof String aid)||aid.length()>200)throw new IOException("Invalid approval");s.status="WAITING_APPROVAL";s.turns.getLast().status=s.status;}
        if("approval_resolved".equals(type)) {
            Object approvalId=event.get("approvalId");String turnId=s.turns.getLast().id;
            boolean requested=s.events.stream().anyMatch(e->"approval_required".equals(e.get("type"))&&Objects.equals(approvalId,e.get("approvalId"))&&turnId.equals(e.get("turnId")));
            boolean resolved=s.events.stream().anyMatch(e->"approval_resolved".equals(e.get("type"))&&Objects.equals(approvalId,e.get("approvalId"))&&turnId.equals(e.get("turnId")));
            if(!requested||resolved||!(event.get("approved") instanceof Boolean))return;
            if("WAITING_APPROVAL".equals(s.status)){s.status="RUNNING";s.turns.getLast().status="RUNNING";}
        }
        if("error".equals(type)){event.clear();event.put("seq",s.events.size()+1);event.put("turnId",s.turns.getLast().id);event.put("type","error");event.put("message","运行时报告错误，请检查工具执行状态。");}
        s.events.add(event);s.updatedAt=now();save(s);
    }
    public synchronized Session approve(String id,String approvalId,boolean approved)throws IOException {
        Session s=session(id);var run=active.get(key(id));if(!ACTIVE.contains(s.status)||run==null)throw new IllegalStateException("任务已结束或中断，不能继续旧审批");
        String turnId=s.turns.getLast().id;
        boolean pending=s.events.stream().anyMatch(e->"approval_required".equals(e.get("type"))&&approvalId.equals(e.get("approvalId"))&&turnId.equals(e.get("turnId")))&&!s.events.stream().anyMatch(e->"approval_resolved".equals(e.get("type"))&&approvalId.equals(e.get("approvalId"))&&turnId.equals(e.get("turnId")));
        if(!pending)throw new IllegalStateException("审批不存在或已处理");
        addEvent(s,"approval_resolved",Map.of("approvalId",approvalId,"approved",approved));s.status="RUNNING";s.turns.getLast().status="RUNNING";save(s);
        run.commands.add(Map.of("id",UUID.randomUUID().toString(),"type","approve","approvalId",approvalId,"approved",approved));return s;
    }
    public synchronized Session cancel(String id)throws IOException {Session s=session(id);if(ACTIVE.contains(s.status)){finish(s,"CANCELLED","任务已停止；已经执行的文件修改保留，请审查差异。");save(s);var run=active.get(key(id));if(run!=null)run.commands.add(Map.of("id",UUID.randomUUID().toString(),"type","cancel"));}return s;}
    public synchronized List<Change> diff(String id)throws IOException {
        Session s=session(id);Path baseline=ownerRoot().resolve("runtime").resolve(s.id).resolve("baseline.json");if(!Files.isRegularFile(baseline))return List.of();
        Map<String,String> before=json.readValue(baseline.toFile(),new TypeReference<>(){}),after=snapshot(workspace(s.projectId));var names=new TreeSet<>(before.keySet());names.addAll(after.keySet());var result=new ArrayList<Change>();
        for(String name:names)if(!Objects.equals(before.get(name),after.get(name)))result.add(new Change(name,before.get(name),after.get(name),!before.containsKey(name)?"added":!after.containsKey(name)?"deleted":"modified"));return result;
    }
    private Map<String,String> snapshot(Path workspace)throws IOException {
        var result=new TreeMap<String,String>();int bytes=0;
        for(Path p:projectFiles(workspace)){
            String name=relative(workspace,p);if(!allowedPath(name))continue;safeFile(workspace,name);
            if(Files.size(p)>FILE_LIMIT)continue;String text;try{text=readText(p);}catch(IllegalArgumentException|java.nio.charset.MalformedInputException e){continue;}
            bytes+=text.getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_SNAPSHOT||result.size()>=MAX_FILES)throw new IllegalStateException("文本工作区超过8MiB或1000文件，请缩小项目");result.put(name,text);
        }return result;
    }
    private static List<Path> projectFiles(Path workspace)throws IOException {
        var result=new ArrayList<Path>();
        Files.walkFileTree(workspace,EnumSet.noneOf(FileVisitOption.class),20,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{
                if(!dir.equals(workspace)&&(!allowedPath(relative(workspace,dir))||Files.isSymbolicLink(dir)))return FileVisitResult.SKIP_SUBTREE;
                if(!dir.toRealPath().startsWith(workspace.toRealPath()))return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{
                if(attrs.isRegularFile()&&allowedPath(relative(workspace,path))){safeFile(workspace,relative(workspace,path));result.add(path);if(result.size()>MAX_FILES)throw new IllegalStateException("项目文件超过1000，请缩小工作区");}
                return FileVisitResult.CONTINUE;
            }
        });result.sort(Comparator.naturalOrder());return result;
    }
    private boolean hasActiveWorker(){String prefix=UserContext.current().id()+":";return active.keySet().stream().anyMatch(k->k.startsWith(prefix));}
    private void assertProjectIdle(String projectId)throws IOException {if(hasActiveWorker()||sessions().stream().anyMatch(s->s.projectId.equals(projectId)&&ACTIVE.contains(s.status)))throw new IllegalStateException("项目正在执行，请停止任务后编辑文件");}
    private Path ownerRoot()throws IOException {Path owner=root.resolve(UserContext.current().id());if(Files.isSymbolicLink(owner))throw new IOException("Invalid owner directory");Files.createDirectories(owner);return owner;}
    private Path workspace(String id)throws IOException {uuid(id);Path project=ownerRoot().resolve("projects").resolve(id);Path workspace=project.resolve("workspace");if(!Files.isRegularFile(project.resolve("project.json"),LinkOption.NOFOLLOW_LINKS)||!Files.isDirectory(workspace,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("项目不存在");if(Files.isSymbolicLink(project)||Files.isSymbolicLink(workspace))throw new IllegalArgumentException("不支持符号链接工作区");return workspace;}
    private Path sessionPath(String id)throws IOException {uuid(id);Path folder=ownerRoot().resolve("sessions");Files.createDirectories(folder);return folder.resolve(id+".json");}
    private static Path safeFile(Path workspace,String name)throws IOException {
        if(!allowedPath(name))throw new IllegalArgumentException("文件路径不在允许的项目范围");Path target=workspace.resolve(name).normalize();if(!target.startsWith(workspace)||target.equals(workspace))throw new IllegalArgumentException("文件路径不在项目范围");
        Path current=workspace;for(Path part:workspace.relativize(target)){current=current.resolve(part);if(Files.isSymbolicLink(current))throw new IllegalArgumentException("不支持符号链接文件");if(Files.exists(current)&&!current.toRealPath().startsWith(workspace.toRealPath()))throw new IllegalArgumentException("文件链接越出项目范围");}
        return target;
    }
    private static boolean allowedPath(String name){if(name==null||name.isBlank()||name.length()>500||name.contains("\\")||name.contains(":")||name.startsWith("/")||name.indexOf('\0')>=0)return false;for(String part:name.split("/")){if(part.isBlank()||part.equals(".")||part.equals("..")||DENIED_SEGMENTS.contains(part.toLowerCase(Locale.ROOT))||part.toLowerCase(Locale.ROOT).startsWith(".env.")||part.endsWith(".")||part.endsWith(" ")||part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))return false;}return true;}
    private static String readText(Path file)throws IOException {if(Files.size(file)>FILE_LIMIT)throw new IllegalArgumentException("文件超过256KiB");String text=Files.readString(file,StandardCharsets.UTF_8);if(text.indexOf('\0')>=0)throw new IllegalArgumentException("仅支持UTF-8文本文件");return text;}
    private static String relative(Path root,Path file){return root.relativize(file).toString().replace('\\','/');}
    private static String mode(String mode){if(mode==null)return "on-risk";if(!Set.of("ask","on-risk","allow").contains(mode))throw new IllegalArgumentException("无效审批模式");return mode;}
    private static void uuid(String id){if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException("无效资源ID");}
    private static String now(){return Instant.now().toString();}
    private static String key(String id){return UserContext.current().id()+":"+id;}
    private void save(Session s)throws IOException {write(sessionPath(s.id),s);}
    private void write(Path path,Object value)throws IOException {Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),".coding-",".tmp");try{json.writeValue(temp.toFile(),value);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}}
    private static void addEvent(Session s,String type,Map<String,Object> data){var e=new LinkedHashMap<String,Object>(data);e.put("seq",s.events.size()+1);e.put("type",type);e.put("turnId",s.turns.isEmpty()?"":s.turns.getLast().id);e.put("createdAt",now());s.events.add(e);s.updatedAt=now();}
    private static void finish(Session s,String status,String message){s.status=status;if(!s.turns.isEmpty())s.turns.getLast().status=status;addEvent(s,"status",message==null?Map.of("status",status):Map.of("status",status,"message",message));}
    @PreDestroy public synchronized void close(){for(var run:active.values())run.commands.offer(Map.of("type","cancel","id",UUID.randomUUID().toString()));executor.shutdown();}
}

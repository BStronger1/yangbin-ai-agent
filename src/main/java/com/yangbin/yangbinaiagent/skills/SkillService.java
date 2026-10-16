package com.yangbin.yangbinaiagent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Versioned account-owned instruction profiles. Skills never contain executable code or credentials. */
@Service
public class SkillService {
    public record Skill(String id,String name,String description,String instructions,boolean useKnowledge,
                        boolean exportPdf,int version,boolean builtin,boolean enabled,String updatedAt) {}
    public record Input(String name,String description,String instructions,boolean useKnowledge,
                        boolean exportPdf,Integer version,Boolean enabled) {}
    public record Stored(String owner,Skill skill) {}
    private static final String UUID_PATTERN="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final List<Skill> BUILTINS=List.of(
        new Skill("builtin-city","城市出行规划","结合天气、地图和个人资料，安排预算内的两人出行。",
            "根据用户的出行目标安排两人行程，兼顾时间、预算、天气与交通偏好。仅使用提供的候选地点；费用为估算，注明未核实的营业状态和交通耗时。按需求选择是否检索个人知识库和生成 PDF。",
            true,true,1,true,true,"2026-10-16T00:00:00Z"),
        new Skill("builtin-rain","雨天室内安排","关注天气，优先考虑室内活动并准备替代方案。",
            "优先考虑有依据的室内活动，提供雨天备选方案。只有天气预报支持时才声称当天有雨；无天气依据时明确说明。费用保持在预算内，不虚构营业信息。按需求选择个人知识库和 PDF。",
            true,true,1,true,true,"2026-10-16T00:00:00Z")
    );
    private final ObjectMapper json;
    private final Path root;
    private final Map<String,Stored> custom=new HashMap<>();
    public SkillService(ObjectMapper json,@Value("${app.skills.dir:tmp/skills}") String dir)throws IOException {
        this.json=json;root=Path.of(dir);Files.createDirectories(root);
        try(var files=Files.list(root)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches(UUID_PATTERN+"\\.json")).toList()) {
                Stored row=json.readValue(file.toFile(),Stored.class);
                if(row==null||!UserContext.validId(row.owner())||row.skill()==null||!file.getFileName().toString().equals(row.skill().id()+".json")
                        ||row.skill().builtin()||row.skill().version()<1)throw new IOException("Invalid stored skill");
                try {validate(new Input(row.skill().name(),row.skill().description(),row.skill().instructions(),row.skill().useKnowledge(),row.skill().exportPdf(),row.skill().version(),row.skill().enabled()));Instant.parse(row.skill().updatedAt());}
                catch(RuntimeException invalid){throw new IOException("Invalid stored skill");}
                custom.put(row.skill().id(),row);
            }
        }
    }
    public synchronized List<Skill> list() {
        String owner=UserContext.current().id();var out=new ArrayList<>(BUILTINS);
        custom.values().stream().filter(row->owner.equals(row.owner())).map(Stored::skill)
            .sorted(Comparator.comparing(Skill::updatedAt).reversed().thenComparing(Skill::id)).forEach(out::add);
        return List.copyOf(out);
    }
    public synchronized Skill get(String id) {
        UserContext.current();
        for(var skill:BUILTINS)if(skill.id().equals(id))return skill;
        return owned(id).skill();
    }
    public synchronized Skill resolve(String id) {
        Skill skill=get(id);if(!skill.enabled())throw new IllegalArgumentException("技能已停用，请选择其他技能");return skill;
    }
    public synchronized Skill create(Input input)throws IOException {
        validate(input);String owner=UserContext.current().id();
        if(custom.values().stream().filter(row->owner.equals(row.owner())).count()>=100)throw new IllegalStateException("最多保存 100 个个人技能，请先整理已有技能");
        Skill skill=from(UUID.randomUUID().toString(),input,1,input.enabled()==null||input.enabled());
        Stored row=new Stored(owner,skill);persist(row);custom.put(skill.id(),row);return skill;
    }
    public synchronized Skill update(String id,Input input)throws IOException {
        Stored previous=owned(id);validate(input);
        if(input.version()==null||input.version()<1||input.enabled()==null)throw new IllegalArgumentException("请提供当前技能版本和启用状态");
        expected(previous.skill(),input.version());
        Skill skill=from(id,input,previous.skill().version()+1,input.enabled());Stored row=new Stored(previous.owner(),skill);
        persist(row);custom.put(id,row);return skill;
    }
    public synchronized void delete(String id,int version)throws IOException {
        Stored row=owned(id);if(version<1)throw new IllegalArgumentException("请提供当前技能版本");expected(row.skill(),version);
        Files.delete(path(id));custom.remove(id);
    }
    private Stored owned(String id) {
        String owner=UserContext.current().id();
        if(BUILTINS.stream().anyMatch(skill->skill.id().equals(id)))throw new IllegalArgumentException("内置技能只读，可以复制为个人技能");
        if(id==null||!id.matches(UUID_PATTERN))throw new IllegalArgumentException("技能不存在");
        Stored row=custom.get(id);if(row==null||!owner.equals(row.owner()))throw new IllegalArgumentException("技能不存在");return row;
    }
    private static void expected(Skill skill,int version){if(skill.version()!=version)throw new IllegalStateException("技能已有新版本，请重新读取后再修改");}
    private Skill from(String id,Input input,int version,boolean enabled) {
        return new Skill(id,input.name().strip(),input.description().strip(),input.instructions().strip(),input.useKnowledge(),input.exportPdf(),version,false,enabled,Instant.now().toString());
    }
    private static void validate(Input input) {
        if(input==null||input.name()==null||input.name().isBlank()||input.name().length()>60)throw new IllegalArgumentException("技能名称需为 1–60 字");
        if(input.description()==null||input.description().length()>300)throw new IllegalArgumentException("技能简介不能超过 300 字");
        if(input.instructions()==null||input.instructions().isBlank()||input.instructions().length()>4000)throw new IllegalArgumentException("技能要求需为 1–4000 字");
    }
    private Path path(String id){if(!id.matches(UUID_PATTERN))throw new IllegalArgumentException("技能不存在");return root.resolve(id+".json");}
    private void persist(Stored row)throws IOException {
        Path temporary=Files.createTempFile(root,"skill-",".tmp");
        try {json.writeValue(temporary.toFile(),row);Files.move(temporary,path(row.skill().id()),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(temporary);}
    }
}

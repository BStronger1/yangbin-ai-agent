package com.yangbin.yangbinaiagent.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

@Component
public class WorkCatalog {
    public record Skill(String id, String name, String category, String icon, String color, String description,
                        String output, String instructions, String example, List<String> tags, int version, String kind) {}
    public record Partner(String id, String name, String skillId, String description, String category,
                          String color, String initial, List<String> tags, String workspace, List<String> skillIds) {
        public Partner {skillIds=skillIds==null?List.of(skillId):List.copyOf(skillIds);}
    }
    public record Catalog(String version, List<Skill> skills, List<Partner> partners) {}
    private final Catalog catalog;
    public WorkCatalog(ObjectMapper json) throws IOException {
        try (var stream = getClass().getResourceAsStream("/work/catalog.json")) {
            if (stream == null) throw new IOException("Missing work catalog");
            catalog = json.readValue(stream, Catalog.class);
        }
        var ids = new HashSet<String>();
        for (var s : catalog.skills()) {
            if (!ids.add(s.id()) || !s.id().matches("[a-z][a-z0-9-]{1,60}") || s.version() < 1
                || !Set.of("TEXT","TRAVEL").contains(s.kind()) || s.name().isBlank()
                || ("TEXT".equals(s.kind()) && s.instructions().isBlank())) throw new IOException("Invalid work skill");
        }
        ids.clear();
        for (var partner : catalog.partners()) {
            if (!ids.add(partner.id()) || !"TEXT".equals(skill(partner.skillId()).kind())
                || (partner.workspace() != null && !"narrative-ppt".equals(partner.workspace())))
                throw new IOException("Invalid work partner");
            if(partner.skillIds().isEmpty()||!partner.skillIds().contains(partner.skillId())||new HashSet<>(partner.skillIds()).size()!=partner.skillIds().size()||partner.skillIds().stream().anyMatch(id->!"TEXT".equals(skill(id).kind())))throw new IOException("Invalid partner skills");
        }
    }
    public Catalog all() { return catalog; }
    public Skill skill(String id) {
        return catalog.skills().stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("技能不存在"));
    }
    public Partner partner(String id) {
        return catalog.partners().stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("伙伴不存在"));
    }
}

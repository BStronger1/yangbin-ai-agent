package com.yangbin.yangbinaiagent.tasks;

import com.yangbin.yangbinaiagent.planner.PlannerService;
import com.yangbin.yangbinaiagent.planner.PlanningEvidence;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.yangbin.yangbinaiagent.skills.SkillService;
import java.util.List;
import java.util.Map;

/** Approved travel capabilities shared by the legacy sequence and bounded skill plans. */
@Component
public class TaskWorkflow {
    private final PlannerService planner;
    private final PlanningEvidence live;
    private final SkillService skills;
    private final TaskPlanningService planning;
    public TaskWorkflow(PlannerService planner, PlanningEvidence live) { this(planner,live,null,null); }
    @Autowired
    public TaskWorkflow(PlannerService planner,PlanningEvidence live,SkillService skills,TaskPlanningService planning) {
        this.planner=planner;this.live=live;this.skills=skills;this.planning=planning;
    }
    public SkillService.Skill skill(String id) {
        if(skills==null)throw new IllegalStateException("技能服务尚未启用");
        return skills.resolve(id);
    }
    public TaskPlanningService.ExecutionPlan plan(PlannerService.Requirements requirements,String prompt,SkillService.Skill skill) throws Exception {
        if(planning==null)throw new IllegalStateException("执行规划服务尚未启用");
        return planning.plan(requirements,prompt,skill);
    }
    public void validate(PlannerService.Requirements requirements) { PlannerService.validate(requirements); }
    public List<Map<String,Object>> sources(PlannerService.Requirements requirements) { return planner.retrieveSources(requirements); }
    public PlanningEvidence.Snapshot evidence(PlannerService.Requirements requirements) { return live.fetch(requirements.city(),requirements.date()); }
    public PlannerService.Itinerary draft(PlannerService.Requirements requirements, String prompt,
            List<Map<String,Object>> sources, PlanningEvidence.Snapshot evidence) throws Exception {
        var output=planner.draft(requirements,prompt,sources,evidence,null);
        // Invalid model output stays in the model step so retry can request a corrected draft.
        planner.validateDraft(output,requirements,evidence);
        return output;
    }
    public PlannerService.Plan save(String taskId, PlannerService.Requirements requirements,
            PlannerService.Itinerary itinerary, List<Map<String,Object>> sources, PlanningEvidence.Snapshot evidence) throws Exception {
        return planner.saveTaskPlan(taskId,requirements,itinerary,sources,evidence);
    }
    public String export(PlannerService.Plan plan) throws Exception { return planner.exportTaskPdf(plan); }
}

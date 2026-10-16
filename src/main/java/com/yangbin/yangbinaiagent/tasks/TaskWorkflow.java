package com.yangbin.yangbinaiagent.tasks;

import com.yangbin.yangbinaiagent.planner.PlannerService;
import com.yangbin.yangbinaiagent.planner.PlanningEvidence;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** The first workflow is deliberately a fixed, inspectable travel workflow. */
@Component
public class TaskWorkflow {
    private final PlannerService planner;
    private final PlanningEvidence live;
    public TaskWorkflow(PlannerService planner, PlanningEvidence live) { this.planner=planner; this.live=live; }
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

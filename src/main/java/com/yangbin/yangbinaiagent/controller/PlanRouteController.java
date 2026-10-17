package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.planner.PlanRouteService;
import com.yangbin.yangbinaiagent.planner.PlannerService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai/plans")
public class PlanRouteController {
    private final PlannerService plans;
    private final PlanRouteService routes;
    public PlanRouteController(PlannerService plans, PlanRouteService routes) {
        this.plans = plans; this.routes = routes;
    }
    @GetMapping("/{id}/{version}/routes")
    public PlanRouteService.Report routes(@PathVariable String id, @PathVariable int version) throws Exception {
        var plan = plans.get(id, version);
        return routes.inspect(plan);
    }
}

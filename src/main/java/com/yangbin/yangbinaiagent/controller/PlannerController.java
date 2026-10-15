package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.planner.PlannerService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/ai/plans")
public class PlannerController {
 private final PlannerService service;private final com.yangbin.yangbinaiagent.planner.PlanningEvidence evidence;public PlannerController(PlannerService s,com.yangbin.yangbinaiagent.planner.PlanningEvidence evidence){service=s;this.evidence=evidence;}
 @GetMapping("/evidence") public Object evidence(@RequestParam String city,@RequestParam String date){return evidence.fetch(city,date);}
 @GetMapping public Object list()throws Exception{return service.list();}
 @PostMapping public Object create(@RequestBody PlannerService.Requirements r)throws Exception{return service.generate(r,null,null);}
 @GetMapping("/{id}") public Object versions(@PathVariable String id)throws Exception{return service.versions(id);}
 public record Revision(int expectedVersion,PlannerService.Requirements requirements){}
 @PostMapping("/{id}/revise") public Object revise(@PathVariable String id,@RequestBody Revision r)throws Exception{return service.generate(r.requirements(),id,r.expectedVersion());}
 @PostMapping("/{id}/{version}/pdf") public Object pdf(@PathVariable String id,@PathVariable int version)throws Exception{return Map.of("url",service.export(id,version));}
}

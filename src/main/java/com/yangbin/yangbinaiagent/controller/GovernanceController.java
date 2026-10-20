package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.governance.ModelGovernance;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/ai/governance")
public class GovernanceController {
 private final ModelGovernance governance;
 public GovernanceController(ModelGovernance governance){this.governance=governance;}
 @GetMapping public Object summary(){return governance.summary();}
 @GetMapping("/events") public Object events(){return governance.events();}
}

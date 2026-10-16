package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.skills.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai/skills")
public class SkillController {
    private final SkillService skills;
    public SkillController(SkillService skills){this.skills=skills;}
    @GetMapping public List<SkillService.Skill> list(){return skills.list();}
    @GetMapping("/{id}") public SkillService.Skill get(@PathVariable String id){return skills.get(id);}
    @PostMapping public SkillService.Skill create(@RequestBody SkillService.Input input)throws Exception{return skills.create(input);}
    @PutMapping("/{id}") public SkillService.Skill update(@PathVariable String id,@RequestBody SkillService.Input input)throws Exception{return skills.update(id,input);}
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id,@RequestParam int version)throws Exception{skills.delete(id,version);return ResponseEntity.noContent().build();}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException error){return ResponseEntity.badRequest().body(Map.of("message",error.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException error){return ResponseEntity.status(409).body(Map.of("message",error.getMessage()));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unavailable(Exception error){return ResponseEntity.status(503).body(Map.of("message","技能服务暂不可用，请稍后重试"));}
}

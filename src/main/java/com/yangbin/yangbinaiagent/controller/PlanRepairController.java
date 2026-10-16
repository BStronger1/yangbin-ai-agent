package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.planner.PlanRepairService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/ai/plans/{id}/repair")
public class PlanRepairController {
    private final PlanRepairService repairs;
    public PlanRepairController(PlanRepairService repairs) {this.repairs=repairs;}
    public record Request(int expectedVersion) {}
    @GetMapping public PlanRepairService.View get(@PathVariable String id) throws Exception {return repairs.get(id);}
    @PostMapping public PlanRepairService.View repair(@PathVariable String id,@RequestBody Request request) throws Exception {
        if(request==null||request.expectedVersion()<1)throw new IllegalArgumentException("请提供当前方案版本");
        return repairs.repair(id,request.expectedVersion());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException failure) {return ResponseEntity.badRequest().body(Map.of("message",failure.getMessage()));}
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException failure) {return ResponseEntity.status(409).body(Map.of("message",failure.getMessage()));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception failure) {return ResponseEntity.status(503).body(Map.of("message","纠错服务暂不可用，请稍后查看状态；请勿假定模型请求尚未发出"));}
}

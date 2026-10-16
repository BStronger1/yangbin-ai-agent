package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.planner.PlannerService;
import com.yangbin.yangbinaiagent.tasks.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai/tasks")
public class TaskController {
    private final TaskService tasks;
    public TaskController(TaskService tasks) { this.tasks=tasks; }
    public record Create(String requestId,String prompt,PlannerService.Requirements requirements,String skillId) {}
    @PostMapping public TaskService.TaskView create(@RequestBody Create body) throws Exception {
        if(body==null)throw new IllegalArgumentException("请填写任务要求");
        return tasks.create(body.requestId(),body.prompt(),body.requirements(),body.skillId());
    }
    @GetMapping public List<TaskService.TaskView> list() { return tasks.list(); }
    @GetMapping("/{id}") public TaskService.TaskView get(@PathVariable String id) { return tasks.get(id); }
    @PostMapping("/{id}/start") public TaskService.TaskView start(@PathVariable String id) throws Exception { return tasks.start(id); }
    @PostMapping("/{id}/retry") public TaskService.TaskView retry(@PathVariable String id) throws Exception { return tasks.retry(id); }
    @PostMapping("/{id}/cancel") public TaskService.TaskView cancel(@PathVariable String id) throws Exception { return tasks.cancel(id); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message",error.getMessage())); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException error) { return ResponseEntity.status(409).body(Map.of("message",error.getMessage())); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception error) { return ResponseEntity.status(503).body(Map.of("message","任务服务暂不可用，请稍后重试")); }
}

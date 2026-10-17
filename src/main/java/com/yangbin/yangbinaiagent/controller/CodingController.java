package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.coding.CodingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/ai/coding")
public class CodingController {
    private final CodingService service;
    public CodingController(CodingService service){this.service=service;}
    public record Approval(boolean approved) {}
    @GetMapping("/capabilities") public Map<String,Object> capabilities(@RequestParam(required=false) String modelProfileId)throws Exception{return service.capabilities(modelProfileId);}
    @GetMapping("/projects") public Object projects()throws Exception{return service.projects();}
    @PostMapping("/projects") public Object createProject(@RequestBody CodingService.ProjectInput input)throws Exception{return service.createProject(input);}
    @GetMapping("/projects/{id}/files") public Object files(@PathVariable String id)throws Exception{return service.files(id);}
    @GetMapping("/projects/{id}/file") public Object file(@PathVariable String id,@RequestParam String path)throws Exception{return service.file(id,path);}
    @PutMapping("/projects/{id}/file") public Object putFile(@PathVariable String id,@RequestBody CodingService.FileInput input)throws Exception{return service.putFile(id,input);}
    @GetMapping("/sessions") public Object sessions()throws Exception{return service.sessions();}
    @PostMapping("/sessions") public Object createSession(@RequestBody CodingService.SessionInput input)throws Exception{return service.createSession(input);}
    @GetMapping("/sessions/{id}") public Object session(@PathVariable String id)throws Exception{return service.session(id);}
    @PostMapping("/sessions/{id}/turns") public ResponseEntity<?> submit(@PathVariable String id,@RequestBody CodingService.TurnInput input)throws Exception{return ResponseEntity.accepted().body(service.submit(id,input));}
    @PostMapping("/sessions/{id}/approvals/{approvalId}") public Object approve(@PathVariable String id,@PathVariable String approvalId,@RequestBody Approval input)throws Exception{return service.approve(id,approvalId,input.approved());}
    @PostMapping("/sessions/{id}/cancel") public Object cancel(@PathVariable String id)throws Exception{return service.cancel(id);}
    @GetMapping("/sessions/{id}/diff") public Object diff(@PathVariable String id)throws Exception{return service.diff(id);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> error(Exception e){return ResponseEntity.status(503).body(Map.of("message","Coding Agent 服务暂不可用，请刷新查看已保存的任务状态。"));}
}

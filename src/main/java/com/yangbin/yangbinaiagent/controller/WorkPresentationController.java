package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.work.WorkPresentationService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/ai/work/runs")
public class WorkPresentationController {
    private final WorkPresentationService service;
    public WorkPresentationController(WorkPresentationService service) { this.service=service; }
    @PostMapping("/{id}/presentation") public WorkPresentationService.Artifact export(@PathVariable String id) throws Exception { return service.export(id); }
    @GetMapping("/{id}/presentation") public ResponseEntity<byte[]> download(@PathVariable String id) throws Exception {
        byte[] bytes=service.download(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("yangbin-"+id+".pptx").build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").contentLength(bytes.length).body(bytes);
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failed(Exception e) {
        return ResponseEntity.status(503).body(Map.of("message","演示文件生成或读取未完成，请稍后重试；不会再次调用模型。"));
    }
}

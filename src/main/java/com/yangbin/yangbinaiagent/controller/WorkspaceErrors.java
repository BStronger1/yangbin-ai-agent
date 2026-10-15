package com.yangbin.yangbinaiagent.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.Map;
@RestControllerAdvice(assignableTypes={KnowledgeController.class,McpController.class,PlannerController.class})
public class WorkspaceErrors {
 @ExceptionHandler({IllegalArgumentException.class,MaxUploadSizeExceededException.class})
 public ResponseEntity<?> bad(Exception e){return ResponseEntity.badRequest().body(Map.of("message",e instanceof MaxUploadSizeExceededException?"文件过大，最多 1 MB":e.getMessage()));}
 @ExceptionHandler(IllegalStateException.class)
 public ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));}
 @ExceptionHandler(Exception.class)
 public ResponseEntity<?> fail(Exception e){return ResponseEntity.status(503).body(Map.of("message","操作暂未完成，请检查服务状态后重试"));}
}

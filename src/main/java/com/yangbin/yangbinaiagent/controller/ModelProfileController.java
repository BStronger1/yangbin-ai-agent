package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.models.ModelProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/ai/models")
public class ModelProfileController {
    private final ModelProfileService service;
    public ModelProfileController(ModelProfileService service){this.service=service;}
    public record DefaultInput(String modelProfileId){}
    @GetMapping public Object catalog()throws Exception{return service.catalog();}
    @PostMapping("/profiles") public Object create(@RequestBody ModelProfileService.Input input)throws Exception{return service.create(input);}
    @PutMapping("/profiles/{id}") public Object update(@PathVariable String id,@RequestBody ModelProfileService.Input input)throws Exception{return service.update(id,input);}
    @DeleteMapping("/profiles/{id}") public Object delete(@PathVariable String id)throws Exception{service.delete(id);return Map.of("deleted",true);}
    @PutMapping("/defaults") public Object defaults(@RequestBody DefaultInput input)throws Exception{return service.defaults(input.modelProfileId());}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unavailable(Exception e){return ResponseEntity.status(503).body(Map.of("message","模型配置暂不可用，请稍后重试"));}
}

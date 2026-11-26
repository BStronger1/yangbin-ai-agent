package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.blog.BlogService;
import com.yangbin.yangbinaiagent.blog.BlogService.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai/blog/posts")
public class BlogController {
    private final BlogService blog;
    public BlogController(BlogService blog) { this.blog = blog; }

    @GetMapping public List<Summary> list() throws IOException { return blog.list(); }
    @GetMapping("/{id}") public Post get(@PathVariable String id) throws IOException { return blog.get(id); }
    @PostMapping public Post create(@RequestBody Edit edit) throws IOException { return blog.create(edit); }
    @PutMapping("/{id}") public Post update(@PathVariable String id, @RequestBody Edit edit) throws IOException {
        return blog.update(id, edit);
    }
    @PostMapping("/{id}/publish") public Post publish(@PathVariable String id, @RequestBody Version version) throws IOException {
        return blog.publish(id, version.revision());
    }
    @PostMapping("/{id}/knowledge") public Imported readIntoKnowledge(@PathVariable String id, @RequestBody Version version) throws IOException {
        return blog.importKnowledge(id, version.revision());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam Integer revision) throws IOException { blog.delete(id, revision); }

    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> badInput(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> conflict(IllegalStateException error) { return Map.of("message", error.getMessage()); }
    @ExceptionHandler(Missing.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> missing() { return Map.of("message", "文章不存在"); }
    @ExceptionHandler(KnowledgeUnavailable.class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> disabled() { return Map.of("message", "知识库未启用或索引暂不可用，文章已保留"); }
    @ExceptionHandler(IOException.class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> storageUnavailable() { return Map.of("message", "存储暂不可用，请稍后重试"); }
}

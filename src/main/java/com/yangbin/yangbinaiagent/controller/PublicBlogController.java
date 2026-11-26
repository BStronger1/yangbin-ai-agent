package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.blog.BlogService;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/open/blog")
public class PublicBlogController {
    private final BlogService blog;
    public PublicBlogController(BlogService blog) { this.blog = blog; }

    @GetMapping("/{id}") public ResponseEntity<BlogService.Published> post(@PathVariable String id) throws IOException {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(blog.publicPost(id));
        } catch (BlogService.Missing ignored) {
            return ResponseEntity.status(404).cacheControl(CacheControl.noStore()).build();
        }
    }
}

package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.constant.FileConstant;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class FileController {
    @GetMapping("/files/{name}")
    public ResponseEntity<FileSystemResource> download(@PathVariable String name) {
        if (!name.matches("[A-Za-z0-9_-]+[.]pdf")) return ResponseEntity.badRequest().build();
        Path path = Path.of(FileConstant.FILE_SAVE_DIR, "pdf", name);
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new FileSystemResource(path));
    }
}

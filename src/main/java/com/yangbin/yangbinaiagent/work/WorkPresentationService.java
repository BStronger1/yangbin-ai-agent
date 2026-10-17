package com.yangbin.yangbinaiagent.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class WorkPresentationService {
    public record Artifact(String runId,String rendererVersion,String contentHash,String sha256,
                           long bytes,int slideCount,String createdAt,String filename,String downloadUrl) {}
    private final WorkService work;
    private final ObjectMapper json;
    private final Path root;
    private final String font;
    public WorkPresentationService(WorkService work,ObjectMapper json,@Value("${app.work.dir:tmp/work}") String dir,
                                   @Value("${app.work.presentation-font:Microsoft YaHei}") String font) {
        this.work=work; this.json=json; this.root=Path.of(dir); this.font=font;
    }
    public synchronized Artifact export(String id) throws IOException {
        var run=eligible(id); Path folder=folder(id); Files.createDirectories(folder);
        String contentHash=hash(json.writeValueAsBytes(Map.of("renderer",PresentationRenderer.VERSION,"font",font,"presentation",run.presentation())));
        Path file=folder.resolve("presentation.pptx"), manifest=folder.resolve("artifact.json");
        if (Files.isRegularFile(file) && Files.isRegularFile(manifest)) {
            try {
                var old=json.readValue(manifest.toFile(),Artifact.class);
                if (valid(old,id,contentHash,file)) return old;
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // A torn/corrupt manifest is rebuildable from the immutable task.
            }
        }
        byte[] bytes=PresentationRenderer.render(run.presentation(),font);
        var artifact=new Artifact(id,PresentationRenderer.VERSION,contentHash,hash(bytes),bytes.length,
            run.presentation().slides().size(),Instant.now().toString(),"yangbin-"+id+".pptx",
            "/api/ai/work/runs/"+id+"/presentation");
        write(file,bytes); write(manifest,json.writeValueAsBytes(artifact));
        return artifact;
    }
    public synchronized byte[] download(String id) throws IOException {
        var run=eligible(id); Path folder=folder(id);
        Path file=folder.resolve("presentation.pptx"),manifest=folder.resolve("artifact.json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(manifest)) throw new IllegalArgumentException("请先生成演示文件");
        var artifact=json.readValue(manifest.toFile(),Artifact.class);
        String contentHash=hash(json.writeValueAsBytes(Map.of("renderer",PresentationRenderer.VERSION,"font",font,"presentation",run.presentation())));
        if (!valid(artifact,id,contentHash,file)) throw new IllegalStateException("演示文件校验未通过，请重新生成文件");
        return Files.readAllBytes(file);
    }
    private WorkService.Run eligible(String id) throws IOException {
        var run=work.get(id); // Ownership and UUID validation must precede every file operation.
        if (!"SUCCEEDED".equals(run.status()) || !"presentation-outline".equals(run.skill().id()) || run.presentation()==null)
            throw new IllegalArgumentException("此任务没有可导出的逐页内容，请使用演示技能新建任务");
        PresentationDraft.validate(run.presentation()); return run;
    }
    private Path folder(String id) { return root.resolve(UserContext.current().id()).resolve("artifacts").resolve(id); }
    private boolean valid(Artifact a,String id,String contentHash,Path file) throws IOException {
        return a!=null && id.equals(a.runId()) && contentHash.equals(a.contentHash())
            && PresentationRenderer.VERSION.equals(a.rendererVersion()) && Files.size(file)==a.bytes()
            && hash(Files.readAllBytes(file)).equals(a.sha256());
    }
    private static void write(Path target,byte[] bytes) throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),"presentation-",".tmp");
        try { Files.write(temporary,bytes); Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

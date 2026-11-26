package com.yangbin.yangbinaiagent.blog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.rag.KnowledgeDocuments;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

@Service
public class BlogService {
    public record Edit(String id, String title, String markdown, List<String> tags, Integer revision) {}
    public record Version(Integer revision) {}
    public record Post(String id, String title, String markdown, List<String> tags, String status, int revision,
                       String createdAt, String updatedAt, String publishedAt) {}
    public record Summary(String id, String title, List<String> tags, String status, int revision,
                          String createdAt, String updatedAt, String publishedAt) {}
    public record Published(String id, String title, String markdown, List<String> tags, String publishedAt) {}
    public record Imported(String documentId, int revision) {}
    // A single atomic file keeps draft changes and the last published snapshot consistent.
    private record Article(Post draft, Published published) {}
    public static final class Missing extends RuntimeException {}
    public static final class KnowledgeUnavailable extends RuntimeException {
        public KnowledgeUnavailable() {}
        public KnowledgeUnavailable(IOException cause) { super(cause); }
    }

    private final ObjectMapper json;
    private final ObjectProvider<KnowledgeDocuments> knowledge;
    private final Path root;
    private final Map<String,String> owners = new HashMap<>();
    private final Object importLock = new Object();

    public BlogService(ObjectMapper json, ObjectProvider<KnowledgeDocuments> knowledge,
                       @Value("${app.blog.dir:tmp/blog}") String directory) throws IOException {
        this.json = json;
        this.knowledge = knowledge;
        root = Path.of(directory);
        Files.createDirectories(root);
        try (var directories = Files.list(root)) {
            for (Path folder : directories.filter(Files::isDirectory).toList()) {
                String owner = folder.getFileName().toString();
                if (!UserContext.validId(owner)) continue;
                try (var files = Files.newDirectoryStream(folder, "*.json")) {
                    for (Path file : files) {
                        String id = file.getFileName().toString().replaceFirst("\\.json$", "");
                        if (validId(id) && owners.putIfAbsent(id, owner) != null)
                            throw new IOException("博客文章编号重复");
                    }
                }
            }
        }
    }

    public synchronized List<Summary> list() throws IOException {
        String owner = UserContext.current().id();
        List<Summary> rows = new ArrayList<>();
        for (var entry : owners.entrySet()) {
            if (!owner.equals(entry.getValue())) continue;
            Article article = read(owner, entry.getKey());
            if (article == null) continue;
            Post post = article.draft();
            rows.add(new Summary(post.id(), post.title(), post.tags(), post.status(), post.revision(),
                    post.createdAt(), post.updatedAt(), post.publishedAt()));
        }
        rows.sort(Comparator.comparing((Summary s) -> Instant.parse(s.updatedAt())).reversed().thenComparing(Summary::id));
        return rows;
    }

    public synchronized Post get(String id) throws IOException { return own(id).draft(); }

    public synchronized Published publicPost(String id) throws IOException {
        String owner = owners.get(id);
        if (owner == null) throw new Missing();
        Article article = read(owner, id);
        if (article == null) throw new Missing();
        Published published = article.published();
        if (published == null) throw new Missing();
        return published;
    }

    public synchronized Post create(Edit edit) throws IOException {
        Edit content = normalize(edit);
        String owner = UserContext.current().id();
        String id = edit.id() == null ? UUID.randomUUID().toString() : edit.id();
        if (!validId(id)) throw new IllegalArgumentException("无效文章编号");
        if (owners.containsKey(id)) {
            if (owner.equals(owners.get(id))) {
                Article existing = read(owner, id);
                if (existing != null && existing.draft().revision() == 1 && matches(existing.draft(), content)) return existing.draft();
            }
            throw new IllegalStateException("创建请求已使用，请刷新文章列表");
        }
        String now = Instant.now().toString();
        Post post = new Post(id, content.title(), content.markdown(), content.tags(), "DRAFT", 1, now, now, null);
        write(owner, id, new Article(post, null));
        owners.put(id, owner);
        return post;
    }

    public synchronized Post update(String id, Edit edit) throws IOException {
        Article article = own(id);
        Edit content = normalize(edit);
        Post before = article.draft();
        checkVersion(before, edit.revision());
        if (matches(before, content)) return before;
        Post post = new Post(id, content.title(), content.markdown(), content.tags(), "DRAFT", before.revision() + 1,
                before.createdAt(), Instant.now().toString(), before.publishedAt());
        write(UserContext.current().id(), id, new Article(post, article.published()));
        return post;
    }

    public synchronized Post publish(String id, Integer revision) throws IOException {
        Post draft = get(id);
        checkVersion(draft, revision);
        if (draft.status().equals("PUBLISHED")) return draft;
        if (draft.title().isBlank() || draft.markdown().isBlank()) throw new IllegalArgumentException("发布需要标题和正文");
        String now = Instant.now().toString();
        Post post = new Post(id, draft.title(), draft.markdown(), draft.tags(), "PUBLISHED", revision + 1,
                draft.createdAt(), now, now);
        Published published = new Published(id, post.title(), post.markdown(), post.tags(), now);
        write(UserContext.current().id(), id, new Article(post, published));
        return post;
    }

    public synchronized void delete(String id, Integer revision) throws IOException {
        checkVersion(get(id), revision);
        // Keep just the reserved ID. A delayed create retry must never reclaim a deleted public link.
        write(UserContext.current().id(), id, null);
    }

    public Imported importKnowledge(String id, Integer revision) throws IOException {
        // Serialize imports in snapshot order without blocking article reads or saves during embedding.
        synchronized (importLock) {
            Post post = get(id);
            checkVersion(post, revision);
            if (!post.status().equals("PUBLISHED")) throw new IllegalStateException("请先发布当前版本，再导入知识库");
            KnowledgeDocuments docs = knowledge.getIfAvailable();
            if (docs == null) throw new KnowledgeUnavailable();
            try {
                var entry = docs.importBlog(id, post.title(), post.markdown());
                return new Imported(entry.id(), post.revision());
            } catch (IOException error) {
                throw new KnowledgeUnavailable(error);
            }
        }
    }

    private static Edit normalize(Edit edit) {
        if (edit == null) throw new IllegalArgumentException("缺少文章内容");
        String title = Objects.toString(edit.title(), "").strip();
        String markdown = Objects.toString(edit.markdown(), "");
        if (title.length() > 120 || title.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("标题最多 120 字符，不能包含控制字符");
        if (markdown.length() > 60000 || markdown.indexOf('\0') >= 0)
            throw new IllegalArgumentException("正文最多 60000 字符，不能包含空字符");
        List<String> tags = edit.tags() == null ? List.of() : edit.tags();
        if (tags.size() > 12) throw new IllegalArgumentException("最多 12 个标签");
        var normalized = new LinkedHashSet<String>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank() || tag.strip().length() > 32 || tag.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("标签须为 1–32 字符");
            normalized.add(tag.strip());
        }
        return new Edit(edit.id(), title, markdown, List.copyOf(normalized), edit.revision());
    }

    private static boolean matches(Post post, Edit content) {
        return post.title().equals(content.title()) && post.markdown().equals(content.markdown()) && post.tags().equals(content.tags());
    }

    private static void checkVersion(Post post, Integer revision) {
        if (revision == null || revision < 1) throw new IllegalArgumentException("缺少有效的文章版本号");
        if (post.revision() != revision) throw new IllegalStateException("文章已在其他设备更新，请重新载入后再编辑");
    }

    private static boolean validId(String id) {
        try {return UUID.fromString(id).toString().equals(id);} catch (IllegalArgumentException ex) {return false;}
    }

    private Path path(String owner, String id) { return root.resolve(owner).resolve(id + ".json"); }

    private Article own(String id) throws IOException {
        String owner = UserContext.current().id();
        if (!owner.equals(owners.get(id))) throw new Missing();
        Article article = read(owner, id);
        if (article == null) throw new Missing();
        return article;
    }

    private Article read(String owner, String id) throws IOException {
        return json.readValue(path(owner, id).toFile(), Article.class);
    }

    private void write(String owner, String id, Article article) throws IOException {
        Path target = path(owner, id);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "post-", ".tmp");
        try {
            json.writeValue(temp.toFile(), article);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

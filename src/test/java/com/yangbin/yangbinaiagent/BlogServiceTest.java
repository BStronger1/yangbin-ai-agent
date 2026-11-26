package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.blog.BlogService;
import com.yangbin.yangbinaiagent.blog.BlogService.Edit;
import com.yangbin.yangbinaiagent.controller.BlogController;
import com.yangbin.yangbinaiagent.controller.PublicBlogController;
import com.yangbin.yangbinaiagent.rag.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BlogServiceTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    final UserContext.User other = new UserContext.User("a".repeat(32), "user");

    BlogService service() throws Exception {
        return new BlogService(json, beans.getBeanProvider(KnowledgeDocuments.class), root.resolve("blog").toString());
    }
    static Edit edit(String title, String text, Integer revision) {
        return new Edit(null, title, text, List.of("笔记"), revision);
    }

    @Test void publishEditAndDeleteKeepPrivateVersionsAndPublicLinksIsolatedAcrossRestart() throws Exception {
        BlogService blog = service();
        String id = UUID.randomUUID().toString();
        Edit initial = new Edit(id, "初稿", "草稿正文", List.of("科技", " 科技 "), null);
        try (var ignored = UserContext.open(UserContext.ADMIN)) {
            var post = blog.create(initial);
            assertThat(blog.create(initial)).isEqualTo(post);
            assertThat(blog.list()).hasSize(1);
            assertThat(post.tags()).containsExactly("科技");
            assertThatThrownBy(() -> blog.publicPost(id)).isInstanceOf(BlogService.Missing.class);
            assertThatThrownBy(() -> blog.importKnowledge(id, 1)).isInstanceOf(IllegalStateException.class);
            blog.publish(id, 1);
            blog.update(id, edit("新标题", "仅作者可见的改动", 2));
            assertThatThrownBy(() -> blog.update(id, edit("覆盖", "来自旧设备", 2))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> blog.delete(id, 2)).isInstanceOf(IllegalStateException.class);
        }
        BlogService restarted = service();
        assertThat(restarted.publicPost(id).markdown()).isEqualTo("草稿正文");
        try (var ignored = UserContext.open(other)) {
            assertThat(restarted.list()).isEmpty();
            assertThatThrownBy(() -> restarted.get(id)).isInstanceOf(BlogService.Missing.class);
            assertThatThrownBy(() -> restarted.publish(id, 3)).isInstanceOf(BlogService.Missing.class);
            assertThatThrownBy(() -> restarted.create(initial)).isInstanceOf(IllegalStateException.class);
        }
        try (var ignored = UserContext.open(UserContext.ADMIN)) {
            var published = restarted.publish(id, 3);
            assertThat(restarted.publicPost(id).markdown()).isEqualTo("仅作者可见的改动");
            restarted.delete(id, published.revision());
            assertThat(service().list()).isEmpty();
            assertThatThrownBy(() -> service().publicPost(id)).isInstanceOf(BlogService.Missing.class);
            assertThatThrownBy(() -> service().create(initial)).isInstanceOf(IllegalStateException.class);
        }
    }

    // Only the embedding provider is simulated. Store, chunking, cosine search and owner filtering use production code.
    float[] vector(String text) {
        if (text.contains("星轨仪")) return new float[]{1, 0, 0};
        if (text.contains("水文仪")) return new float[]{0, 1, 0};
        return new float[]{0, 0, 1};
    }
    KnowledgeDocuments documents(SimpleVectorStore store) throws Exception {
        LoveAppDocumentLoader loader = mock(LoveAppDocumentLoader.class);
        when(loader.loadMarkdowns()).thenReturn(List.of());
        return new KnowledgeDocuments(store, loader, json, root.resolve("knowledge").toString());
    }
    @Test void queryOwnerIndexedArticleThenReplaceLongArticleAndRecoverAfterFailedReimport() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(Document.class))).thenAnswer(i -> vector(i.getArgument(0, Document.class).getText()));
        when(model.embed(anyString())).thenAnswer(i -> vector(i.getArgument(0, String.class)));
        SimpleVectorStore store = SimpleVectorStore.builder(model).build();
        var docs = documents(store);
        beans.registerSingleton("knowledgeDocuments", docs);
        BlogService blog = service();
        var search = new KnowledgeService(store);
        try (var ignored = UserContext.open(UserContext.ADMIN)) {
            var post = blog.create(edit("观测手记", "星轨仪".repeat(400), null));
            int revision = blog.publish(post.id(), 1).revision();
            var imported = blog.importKnowledge(post.id(), revision);
            assertThat(blog.importKnowledge(post.id(), revision)).isEqualTo(imported);
            assertThat(docs.list()).hasSize(1);
            assertThat(search.search("星轨仪")).hasSize(3).allMatch(d -> d.getMetadata().get("documentId").equals(imported.documentId()));
            try (var ignoredOther = UserContext.open(other)) {
                assertThat(search.search("星轨仪")).isEmpty();
                assertThatThrownBy(() -> blog.importKnowledge(post.id(), revision)).isInstanceOf(BlogService.Missing.class);
            }
            int next = blog.update(post.id(), edit("新手记", "水文仪的校准", revision)).revision();
            int latest = blog.publish(post.id(), next).revision();
            doThrow(new IllegalStateException("embedding unavailable")).when(model).embed(any(Document.class));
            assertThatThrownBy(() -> blog.importKnowledge(post.id(), latest)).isInstanceOf(BlogService.KnowledgeUnavailable.class);
            assertThat(search.search("星轨仪")).hasSize(3);
            doAnswer(i -> vector(i.getArgument(0, Document.class).getText())).when(model).embed(any(Document.class));
            assertThat(blog.importKnowledge(post.id(), latest).documentId()).isEqualTo(imported.documentId());
            assertThat(search.search("星轨仪")).isEmpty();
            assertThat(search.search("水文仪")).singleElement().satisfies(d -> assertThat(d.getText()).isEqualTo("水文仪的校准"));
            SimpleVectorStore afterRestart = SimpleVectorStore.builder(model).build();
            var reloaded = documents(afterRestart);
            assertThat(reloaded.detail(imported.documentId()).filename()).isEqualTo("新手记.md");
            assertThat(new KnowledgeService(afterRestart).search("水文仪")).hasSize(1);
            reloaded.delete(imported.documentId());
            assertThat(new KnowledgeService(afterRestart).search("水文仪")).isEmpty();
        }
    }

    @Test void controllerContractUsesConflictsAndExplicitPublicProjection() throws Exception {
        var blog = service();
        var http = MockMvcBuilders.standaloneSetup(new BlogController(blog), new PublicBlogController(blog)).build();
        String id = UUID.randomUUID().toString();
        try (var ignored = UserContext.open(UserContext.ADMIN)) {
            http.perform(post("/ai/blog/posts").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(new Edit(id, "x", "markdown", List.of(), null))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
            http.perform(post("/ai/blog/posts/{id}/publish", id).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
            http.perform(delete("/ai/blog/posts/{id}", id).param("revision","1")).andExpect(status().isConflict());
            http.perform(post("/ai/blog/posts/{id}/knowledge", id).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))
                    .andExpect(status().isServiceUnavailable());
        }
        http.perform(get("/open/blog/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("x"))
                .andExpect(jsonPath("$.revision").doesNotExist()).andExpect(jsonPath("$.owner").doesNotExist());
        http.perform(get("/open/blog/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}

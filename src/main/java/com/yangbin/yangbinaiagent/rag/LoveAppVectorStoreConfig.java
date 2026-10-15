package com.yangbin.yangbinaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;

@Configuration
@ConditionalOnProperty(name="app.rag.enabled", havingValue="true")
public class LoveAppVectorStoreConfig {
    @Bean
    VectorStore loveAppVectorStore(LoveAppDocumentLoader loader,
            @Value("${app.rag.embedding-base-url}") String baseUrl,
            @Value("${app.rag.embedding-api-key:local}") String apiKey,
            @Value("${app.rag.embedding-model}") String model,
            @Value("${app.rag.index-dir:tmp/rag}") String directory) throws Exception {
        var api = OpenAiApi.builder().baseUrl(baseUrl.replaceAll("/+$", "")).apiKey(apiKey).build();
        var embedding = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build());
        var store = SimpleVectorStore.builder(embedding).build();
        List<Document> chunks = new ArrayList<>();
        for (Document doc : loader.loadMarkdowns()) {
            String text = doc.getText();
            for (int start=0; start<text.length(); start+=520) {
                var metadata = new HashMap<>(doc.getMetadata());
                metadata.put("offset", start);
                chunks.add(new Document(text.substring(start, Math.min(start+600,text.length())), metadata));
                if (start+600>=text.length()) break;
            }
        }
        if (chunks.isEmpty()) throw new IllegalStateException("Knowledge documents are missing");
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update((model+baseUrl+"chunk600-overlap80-v1").getBytes(StandardCharsets.UTF_8));
        for (Document d:chunks) digest.update((d.getMetadata()+d.getText()).getBytes(StandardCharsets.UTF_8));
        Path dir=Path.of(directory); Files.createDirectories(dir);
        Path file=dir.resolve(HexFormat.of().formatHex(digest.digest())+".json");
        if (Files.isRegularFile(file)) store.load(file.toFile());
        else { store.add(chunks); store.save(file.toFile()); }
        return store;
    }
}

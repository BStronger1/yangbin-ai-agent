package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.rag.*;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class KnowledgeDocumentsTest {
    com.yangbin.yangbinaiagent.security.UserContext.Scope identity;
    @org.junit.jupiter.api.BeforeEach void identity(){identity=com.yangbin.yangbinaiagent.security.UserContext.open(com.yangbin.yangbinaiagent.security.UserContext.ADMIN);}
    @org.junit.jupiter.api.AfterEach void clearIdentity(){identity.close();}
 @TempDir Path root;
 KnowledgeDocuments manager(VectorStore store)throws Exception{var loader=mock(LoveAppDocumentLoader.class);when(loader.loadMarkdowns()).thenReturn(List.of());return new KnowledgeDocuments(store,loader,new ObjectMapper(),root.toString());}
 @Test void savedDocumentsAreReindexedOnRestartAndDeletedFromDisk()throws Exception{
  var store=mock(VectorStore.class);var first=manager(store);var entry=first.upload(new MockMultipartFile("file","advice.md","text/plain","persistent advice".getBytes(StandardCharsets.UTF_8)));
  var restarted=manager(store);assertThat(restarted.detail(entry.id()).state()).isEqualTo("READY");verify(store,times(2)).add(anyList());
  restarted.delete(entry.id());assertThat(manager(store).list()).isEmpty();assertThat(Files.list(root).toList()).isEmpty();
 }
 @Test void badNamesUnsupportedFilesAndOversizedTextAreRejected()throws Exception{
  var docs=manager(mock(VectorStore.class));
  for(String name:List.of("../outside.md","bad.exe","folder\\bad.txt"))assertThatThrownBy(()->docs.upload(new MockMultipartFile("file",name,"text/plain","text".getBytes()))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->docs.upload(new MockMultipartFile("file","long.txt","text/plain",new byte[1024*1024+1]))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->docs.upload(new MockMultipartFile("file","bad.txt","text/plain",new byte[]{(byte)0xff}))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void failedIndexingDoesNotPublishOrPersistDocument()throws Exception{
  var store=mock(VectorStore.class);doThrow(new RuntimeException("embedding unavailable")).when(store).add(anyList());
  var docs=manager(store);assertThatThrownBy(()->docs.upload(new MockMultipartFile("file","advice.md","text/plain","hello".getBytes()))).isInstanceOf(IllegalStateException.class);
  assertThat(docs.list()).isEmpty();assertThat(Files.list(root).toList()).isEmpty();verify(store).delete(anyList());
 }
}

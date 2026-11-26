package com.yangbin.yangbinaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name="app.rag.enabled",havingValue="true")
public class KnowledgeDocuments {
    public record Entry(String id,String filename,String text,String state,int chunks,boolean builtin,String error,String owner) {
        public Entry {if(owner==null)owner=builtin?"public":"admin";}
    }
    private final Map<String,Entry> entries=new ConcurrentHashMap<>();
    private final VectorStore store;
    private final Path root;
    private final ObjectMapper json;
    public KnowledgeDocuments(@Qualifier("loveAppVectorStore") VectorStore store, LoveAppDocumentLoader loader,
            ObjectMapper json,@Value("${app.rag.upload-dir:tmp/knowledge}") String directory) throws IOException {
        this.store=store;this.json=json;this.root=Path.of(directory);Files.createDirectories(root);
        var groups=new TreeMap<String,StringBuilder>();
        var counts=new HashMap<String,Integer>();
        for(var doc:loader.loadMarkdowns()) counts.merge(doc.getMetadata().get("filename").toString(),chunks("builtin","",doc.getText()).size(),Integer::sum);
        for(var doc:loader.loadMarkdowns()) groups.computeIfAbsent(doc.getMetadata().get("filename").toString(),k->new StringBuilder()).append(doc.getText()).append("\n");
        groups.forEach((name,text)->entries.put("builtin-"+name,new Entry("builtin-"+name,name,text.toString(),"READY",counts.get(name),true,"","public")));
        try(var files=Files.list(root)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("(?:blog-)?[a-f0-9-]{36}\\.json")).toList()) {
                Entry e=json.readValue(file.toFile(),Entry.class);
                try { index(e); } catch(Exception ex) { entries.put(e.id(),new Entry(e.id(),e.filename(),e.text(),"FAILED",0,false,"索引失败，请重试",e.owner())); }
            }
        }
    }
    public List<Entry> list(){return entries.values().stream().filter(this::visible).sorted(Comparator.comparing(Entry::filename)).map(e->new Entry(e.id(),e.filename(),"",e.state(),e.chunks(),e.builtin(),e.error(),e.owner())).toList();}
    private boolean visible(Entry e){return e.builtin()||e.owner().equals(UserContext.current().id());}
    public Entry detail(String id){var e=entries.get(id);if(e==null||!visible(e))throw new IllegalArgumentException("文档不存在");return e;}
    public synchronized Entry upload(MultipartFile file) throws IOException {
        if(file.isEmpty()||file.getSize()>1024*1024)throw new IllegalArgumentException("请选择不超过 1 MB 的文档");
        checkQuota();
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if(name.length()>120||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("无效文件名");
        String lower=name.toLowerCase(Locale.ROOT),text;
        if(lower.endsWith(".pdf")) {
            var out=new StringBuilder();
            try(var pdf=new PdfDocument(new PdfReader(file.getInputStream()))) {
                if(pdf.getNumberOfPages()>100)throw new IllegalArgumentException("PDF 最多 100 页");
                for(int i=1;i<=pdf.getNumberOfPages();i++) {
                    out.append(PdfTextExtractor.getTextFromPage(pdf.getPage(i))).append("\n");
                    if(out.length()>60000)throw new IllegalArgumentException("解析后文本最多 60000 字符");
                }
            }
            text=out.toString();
        } else if(lower.endsWith(".md")||lower.endsWith(".txt")) {
            try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(file.getBytes())).toString();}
            catch(CharacterCodingException ex){throw new IllegalArgumentException("文本文件必须使用 UTF-8 编码");}
        } else throw new IllegalArgumentException("支持 Markdown、TXT 和含文本的 PDF");
        if(text.isBlank()||text.length()>60000||text.indexOf('\0')>=0)throw new IllegalArgumentException("没有可索引文本或超过 60000 字符；扫描 PDF 请先 OCR");
        Entry e=new Entry(UUID.randomUUID().toString(),name,text,"INDEXING",0,false,"",UserContext.current().id());
        entries.put(e.id(),e);
        try {index(e);save(entries.get(e.id()));return detail(e.id());}
        catch(Exception ex){synchronized(store){store.delete(chunkIds(e));}entries.remove(e.id());throw new IllegalStateException("文档索引失败，未保存，请稍后重试",ex);}
    }
    private List<Document> chunks(String id,String name,String text){
        List<Document> out=new ArrayList<>();
        for(int start=0;start<text.length();start+=520){out.add(new Document(id+"-"+start,text.substring(start,Math.min(start+600,text.length())),Map.of("filename",name,"documentId",id,"offset",start)));if(start+600>=text.length())break;}
        return out;
    }
    private void checkQuota() {
        if(entries.values().stream().filter(e->!e.builtin()&&visible(e)).count()>=100)throw new IllegalArgumentException("最多保存 100 个上传文档");
    }

    /** Stable owner-scoped document ID. Stage new chunks under separate IDs so failed embeddings preserve the prior article. */
    public synchronized Entry importBlog(String postId, String title, String markdown) throws IOException {
        String owner = UserContext.current().id();
        String id = "blog-" + UUID.nameUUIDFromBytes((owner + "/" + postId).getBytes(StandardCharsets.UTF_8));
        String filename = title.replace('/', '／').replace('\\', '＼') + ".md";
        Entry before = entries.get(id);
        if (before == null) checkQuota();
        if (before != null && before.state().equals("READY") && before.filename().equals(filename) && before.text().equals(markdown)) return before;
        Entry input = new Entry(id, filename, markdown, "INDEXING", 0, false, "", owner);
        var staged = indexedChunks(input);
        Entry ready = new Entry(id, filename, markdown, "READY", staged.size(), false, "", owner);
        synchronized (store) {
            try {
                store.add(staged);
                save(ready);
            } catch (Exception failure) {
                store.delete(staged.stream().map(Document::getId).toList());
                throw new IOException("文章索引未完成，请稍后重试", failure);
            }
            if (before != null && (!before.filename().equals(filename) || !before.text().equals(markdown))) store.delete(chunkIds(before));
            entries.put(id, ready);
        }
        return ready;
    }

    private List<Document> indexedChunks(Entry e) {
        String version = e.id().startsWith("blog-")
                ? "-" + UUID.nameUUIDFromBytes((e.filename() + "\0" + e.text()).getBytes(StandardCharsets.UTF_8))
                : "";
        return chunks(e.id(),e.filename(),e.text()).stream().map(d -> {
            var metadata = new HashMap<>(d.getMetadata());
            metadata.put("owner",e.owner());
            return new Document(d.getId() + version,d.getText(),metadata);
        }).toList();
    }
    private List<String> chunkIds(Entry e){return indexedChunks(e).stream().map(Document::getId).toList();}
    private void index(Entry e){
        var docs=indexedChunks(e);
        entries.put(e.id(),new Entry(e.id(),e.filename(),e.text(),"INDEXING",docs.size(),false,"",e.owner()));
        synchronized(store){store.add(docs);}
        entries.put(e.id(),new Entry(e.id(),e.filename(),e.text(),"READY",docs.size(),false,"",e.owner()));
    }
    private void save(Entry e)throws IOException{
        Path temp=Files.createTempFile(root,"upload-",".tmp");
        try {json.writeValue(temp.toFile(),e);Files.move(temp,root.resolve(e.id()+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    public synchronized void delete(String id)throws IOException{
        Entry e=detail(id);if(e.builtin())throw new IllegalArgumentException("内置文档不可删除");
        Files.deleteIfExists(root.resolve(e.id()+".json"));synchronized(store){store.delete(chunkIds(e));}entries.remove(id);
    }
    public synchronized Entry reindex(String id)throws IOException{
        Entry e=detail(id);if(e.builtin())throw new IllegalArgumentException("内置文档随应用启动建立索引");
        try {index(e);save(detail(id));return detail(id);}catch(Exception ex){entries.put(id,new Entry(e.id(),e.filename(),e.text(),"FAILED",0,false,"索引失败，请重试",e.owner()));throw new IllegalStateException("索引失败",ex);}
    }
}

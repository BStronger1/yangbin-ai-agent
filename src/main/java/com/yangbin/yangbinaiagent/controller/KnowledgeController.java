package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.rag.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@RestController @RequestMapping("/ai/knowledge")
public class KnowledgeController {
 private final ObjectProvider<KnowledgeDocuments> documents;
 private final ObjectProvider<KnowledgeService> knowledge;
 public KnowledgeController(ObjectProvider<KnowledgeDocuments> d,ObjectProvider<KnowledgeService> k){documents=d;knowledge=k;}
 private KnowledgeDocuments docs(){var d=documents.getIfAvailable();if(d==null)throw new IllegalStateException("RAG 尚未启用");return d;}
 @GetMapping public Object list(){return docs().list();}
 @GetMapping("/{id}") public Object detail(@PathVariable String id){return docs().detail(id);}
 @PostMapping(consumes="multipart/form-data") public Object upload(@RequestParam MultipartFile file)throws Exception{return docs().upload(file);}
 @DeleteMapping("/{id}") public void delete(@PathVariable String id)throws Exception{docs().delete(id);}
 @PostMapping("/{id}/reindex") public Object reindex(@PathVariable String id)throws Exception{return docs().reindex(id);}
 @PostMapping("/evaluate") public Object evaluate(){
   docs();
   String[][] cases={{"单身如何拓展社交圈","单身篇"},{"单身怎样克服社交恐惧","单身篇"},{"恋爱中如何处理争吵","恋爱篇"},{"恋爱中如何保持新鲜感","恋爱篇"},{"婚后家务如何分工","已婚篇"},{"婚后如何处理婆媳关系","已婚篇"}};
   var rows=new ArrayList<Map<String,Object>>();int hits=0;
   for(var c:cases){long start=System.nanoTime();var result=knowledge.getObject().search(c[0]);var sources=result.stream().map(d->d.getMetadata().get("filename").toString()).toList();boolean hit=sources.stream().anyMatch(s->s.contains(c[1]));if(hit)hits++;rows.add(Map.of("query",c[0],"expected",c[1],"sources",sources,"hit",hit,"durationMs",(System.nanoTime()-start)/1000000));}
   return Map.of("cases",rows,"hitAt3",hits/(double)cases.length,"total",cases.length,"description","内置 6 题文件命中测试；不代表答案正确率，不调用聊天模型");
 }
}

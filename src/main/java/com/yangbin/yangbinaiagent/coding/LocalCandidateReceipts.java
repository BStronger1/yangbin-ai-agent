package com.yangbin.yangbinaiagent.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/** Non-executable, owner/session-scoped observations from the device that claimed the operation. */
final class LocalCandidateReceipts {
    record Candidate(String path,String baseHash,String afterHash,String before,String after,boolean conflict) {}
    record Receipt(String operationId,String parametersHash,String phase,String effect,Boolean bytesMatch,Boolean baselineMatches,Boolean writerAlive,Candidate candidate) {}
    record Item(String operationId,String turnId,String path,String parametersHash,String deviceHash,String phase,String claimedAt,String receivedAt,
                String baseHash,String afterHash,Boolean bytesMatch,Boolean baselineMatches,Boolean writerAlive) {}
    private final ObjectMapper json;
    private final Path root;
    LocalCandidateReceipts(Path root,ObjectMapper json){this.root=root;this.json=json;}
    static String sha(String content) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private Path file(String id){if(id==null||!id.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException("无效操作编号");return root.resolve(id+".json");}
    private void store(Path file,Object data)throws IOException{
        Files.createDirectories(file.getParent());
        Path tmp=Files.createTempFile(file.getParent(),"receipt-",".tmp");
        try{json.writeValue(tmp.toFile(),data);Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        finally{Files.deleteIfExists(tmp);}
    }
    private Item read(String id)throws IOException{
        Path f=file(id);if(!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("找不到领取记录");
        return json.readValue(f.toFile(),Item.class);
    }
    boolean claimed(String id){return Files.isRegularFile(file(id),LinkOption.NOFOLLOW_LINKS);}
    @SuppressWarnings("unchecked")
    void claim(String client,Map<String,Object> request)throws IOException{
        String id=(String)request.get("requestId");Path f=file(id);
        if(Files.exists(f))throw new IllegalStateException("操作已被领取");
        Map<String,Object> args=(Map<String,Object>)request.get("arguments");
        var canonical=new TreeMap<String,Object>();canonical.put("args",new TreeMap<>(args));canonical.put("operation",request.get("operation"));
        String digest=sha(json.writeValueAsString(canonical));
        store(f,new Item(id,(String)request.get("turnId"),(String)args.get("path"),digest,sha(client),"CLAIMED",Instant.now().toString(),null,null,null,null,null,null));
    }
    boolean observe(String client,String id,Receipt r)throws IOException{
        Item old=read(id);
        if(client==null||!old.deviceHash.equals(sha(client))||r==null||!id.equals(r.operationId)||!"CANDIDATE".equals(r.effect))throw new IllegalArgumentException("回执与原领取设备或操作不符");
        if("MISSING".equals(r.phase))return false;
        Candidate c=r.candidate;
        if(!Set.of("PREPARED","CONFIRMED").contains(Objects.toString(r.phase,""))||!old.parametersHash.equals(r.parametersHash)||c==null||!old.path.equals(c.path)||
            c.after==null || c.after.getBytes(StandardCharsets.UTF_8).length+(c.before==null?0:c.before.getBytes(StandardCharsets.UTF_8).length)>262144||
            c.after.indexOf('\0')>=0 || c.before!=null&&c.before.indexOf('\0')>=0 || !Objects.equals(c.baseHash,c.before==null?null:sha(c.before))||
            !sha(c.after).equals(c.afterHash)||r.bytesMatch==null||r.writerAlive==null)throw new IllegalArgumentException("候选的路径、摘要或大小不符");
        if(old.afterHash!=null&&(!old.afterHash.equals(c.afterHash)||!Objects.equals(old.baseHash,c.baseHash)))throw new IllegalStateException("同一操作的候选不可变");
        if("CONFIRMED".equals(old.phase)&&!"CONFIRMED".equals(r.phase))return true;
        if("CONFIRMED".equals(r.phase)&&!"CONFIRMED".equals(old.phase))store(root.resolve("candidates").resolve(id+".json"),c);
        store(file(id),new Item(id,old.turnId,old.path,old.parametersHash,old.deviceHash,r.phase,old.claimedAt,Instant.now().toString(),c.baseHash,c.afterHash,r.bytesMatch,r.baselineMatches,r.writerAlive));
        return "CONFIRMED".equals(r.phase);
    }
    List<Map<String,Object>> list(String client)throws IOException{
        if(!Files.isDirectory(root))return List.of();
        var rows=new ArrayList<Map<String,Object>>();
        try(var files=Files.newDirectoryStream(root,"*.json")){
            for(Path f:files){
                Item i=json.readValue(f.toFile(),Item.class);var m=new LinkedHashMap<String,Object>();
                m.put("operationId",i.operationId);m.put("turnId",i.turnId);m.put("path",i.path);m.put("phase",i.phase);m.put("claimedAt",i.claimedAt);
                m.put("receivedAt",i.receivedAt);m.put("onDevice",client!=null&&i.deviceHash.equals(sha(client)));m.put("baseHash",i.baseHash);
                m.put("afterHash",i.afterHash);m.put("bytesMatch",i.bytesMatch);m.put("baselineMatches",i.baselineMatches);m.put("writerAlive",i.writerAlive);rows.add(m);
            }
        }
        rows.sort(Comparator.comparing(row->(String)row.get("claimedAt")));return rows;
    }
    Candidate candidate(String id)throws IOException{
        Item i=read(id);if(!"CONFIRMED".equals(i.phase))throw new IllegalStateException("候选尚无执行器确认");
        Candidate c=json.readValue(root.resolve("candidates").resolve(id+".json").toFile(),Candidate.class);
        if(!sha(c.after).equals(i.afterHash))throw new IOException("候选摘要失配");return c;
    }
}

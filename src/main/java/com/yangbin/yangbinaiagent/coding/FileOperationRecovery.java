package com.yangbin.yangbinaiagent.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Read-only reconciliation. A hash match describes current bytes, never proves execution history. */
final class FileOperationRecovery {
    private final ObjectMapper json;
    FileOperationRecovery(ObjectMapper json){this.json=json;}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String current(Path workspace,String name)throws Exception{
        if(name==null||name.isBlank()||name.startsWith("/")||name.contains(":")||name.contains("\\"))throw new IllegalArgumentException("Invalid operation path");
        Path current=workspace;
        for(String part:name.split("/",-1)){
            if(part.isBlank()||part.equals(".")||part.equals("..")||part.matches("(?i)(\\.git|\\.ssh|\\.aws|\\.codex|\\.pi|node_modules|\\.env.*|credentials.*|auth.*|secrets.*)")||part.startsWith(".coding-write-"))throw new IllegalArgumentException("Protected operation path");
            current=current.resolve(part);if(Files.isSymbolicLink(current))throw new IllegalArgumentException("Linked operation path");
        }
        if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS))return "MISSING";
        if(!Files.isRegularFile(current,LinkOption.NOFOLLOW_LINKS)||Files.size(current)>262144)throw new IllegalArgumentException("Unverifiable file");
        return hash(Files.readAllBytes(current));
    }
    static boolean workerAlive(Path journal)throws Exception{
        Path lock=journal.resolve("writer.lock");if(!Files.exists(lock,LinkOption.NOFOLLOW_LINKS))return false;
        if(Files.isSymbolicLink(lock)||Files.size(lock)>1024)throw new IllegalStateException("Invalid writer lock");
        long pid=new ObjectMapper().readTree(lock.toFile()).path("pid").asLong(-1);
        if(pid<=0)throw new IllegalStateException("Unknown writer owner");
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
    List<Map<String,Object>> inspect(Path journal,Path workspace,String account,String project,String session,String turn)throws Exception{
        var result=new ArrayList<Map<String,Object>>();
        if(!Files.exists(journal,LinkOption.NOFOLLOW_LINKS))return result;
        if(Files.isSymbolicLink(journal)||!journal.toRealPath().equals(journal.toAbsolutePath().normalize()))throw new IllegalStateException("Invalid journal path");
        try(var files=Files.list(journal)){
            var records=files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList();
            if(records.size()>80)throw new IllegalStateException("Journal record limit exceeded");
            for(Path file:records){
                if(Files.isSymbolicLink(file)||Files.size(file)>16000||!file.getFileName().toString().matches("[a-f0-9]{64}\\.json"))throw new IllegalStateException("Invalid operation record");
                var r=json.readTree(file.toFile());var identity=r.path("identity");
                if(r.path("version").asInt()!=1||!account.equals(identity.path("accountId").asText())||!project.equals(identity.path("projectId").asText())||!session.equals(identity.path("sessionId").asText())||!turn.equals(identity.path("taskId").asText()))throw new IllegalStateException("Operation scope mismatch");
                String id=r.path("operationId").asText(),name=r.path("path").asText(),state=r.path("state").asText();
                if(!file.getFileName().toString().equals(id+".json")||!Set.of("PREPARED","APPLIED").contains(state))throw new IllegalStateException("Invalid operation state");
                String before=r.path("beforeHash").isNull()?"MISSING":r.path("beforeHash").asText(),after=r.path("afterHash").asText();
                if((!before.equals("MISSING")&&!before.matches("[a-f0-9]{64}"))||!after.matches("[a-f0-9]{64}"))throw new IllegalStateException("Invalid operation hash");
                String actual,observation;
                try{actual=current(workspace,name);observation=actual.equals(after)?(before.equals(after)?"NOOP_CONTENT_MATCH":"AFTER_MATCH"):actual.equals(before)?"BEFORE_MATCH":"DIVERGED";}
                catch(Exception invalid){actual="UNVERIFIABLE";observation="UNVERIFIABLE";}
                result.add(Map.of("operationId",id,"sessionId",session,"turnId",turn,"path",name,"state",state,"observation",observation,"beforeHash",before,"afterHash",after,"currentHash",actual,"recordHash",hash(Files.readAllBytes(file))));
            }
        }return result;
    }
}

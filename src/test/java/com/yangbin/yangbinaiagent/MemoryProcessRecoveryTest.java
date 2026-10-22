package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.memory.MemoryService;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
/** Independent JVMs reopen the actual store. No running production service is restarted. */
class MemoryProcessRecoveryTest {
 @TempDir Path root;
 @Test void persistedCorrectionAndTombstoneSurviveSeparateJvmRestarts()throws Exception {
  String project=UUID.randomUUID().toString();Path folder=root.resolve("admin/projects/"+project);Files.createDirectories(folder);Files.writeString(folder.resolve("project.json"),"{}");
  for(String phase:List.of("save","correct","recover","forget","forgotten")){
   String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
   Path log=root.resolve(phase+".log");var child=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),MemoryProcessRecoveryTest.class.getName(),root.toString(),project,phase).redirectErrorStream(true).redirectOutput(log.toFile()).start();
   boolean completed=child.waitFor(25,TimeUnit.SECONDS);if(!completed)child.destroyForcibly();assertThat(completed).as(phase).isTrue();assertThat(child.exitValue()).as(phase+": "+Files.readString(log)).isZero();
  }
 }
 public static void main(String[] args)throws Exception {
  try(var scope=UserContext.open(UserContext.ADMIN)){
   var mapper=new ObjectMapper();var memory=new MemoryService(mapper,args[0]);String project=args[1],phase=args[2];Path record=Path.of(args[0],"test-record.json");
   if(phase.equals("save")){var item=memory.create(project,new MemoryService.Create(UUID.randomUUID().toString(),"deployment region","region ap-southeast-1","CONSTRAINT",null,null));mapper.writeValue(record.toFile(),Map.of("id",item.id));}
   else {String id=mapper.readTree(record.toFile()).path("id").asText();
    switch(phase){
     case "correct" -> memory.update(project,id,new MemoryService.Update(1,"deployment region","region eu-west-2","CONSTRAINT","ACTIVE"));
     case "recover" -> {String found=mapper.writeValueAsString(memory.search(project,"deployment region"));if(!found.contains("eu-west-2")||found.contains("ap-southeast-1"))throw new AssertionError("Incorrect recovered revision");}
     case "forget" -> memory.forget(project,id,2);
     case "forgotten" -> {if(!((java.util.List<?>)memory.search(project,"deployment region").get("items")).isEmpty())throw new AssertionError("Forgotten memory recovered");String bytes=Files.readString(Path.of(args[0],"admin/projects",project,"memory.json"));if(bytes.contains("ap-southeast-1")||bytes.contains("eu-west-2"))throw new AssertionError("Forgotten plaintext persisted");}
     default -> throw new AssertionError("Unknown phase");
    }
   }
  }
 }
}

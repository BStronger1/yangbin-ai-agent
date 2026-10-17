package com.yangbin.yangbinaiagent.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

@Service
public class ModelProfileService {
    public static final String SYSTEM="system";
    public record Provider(String id,String name,String api,String defaultBaseUrl,List<String> suggestedModels){}
    public static final List<Provider> PROVIDERS=List.of(
        new Provider("openai","OpenAI","openai-completions","https://api.openai.com/v1",List.of("gpt-5.4","gpt-4.1")),
        new Provider("anthropic","Anthropic / Claude","anthropic-messages","https://api.anthropic.com",List.of("claude-sonnet-4-6","claude-opus-4-6")),
        new Provider("deepseek","DeepSeek","openai-completions","https://api.deepseek.com",List.of("deepseek-flash","deepseek-v4-pro")),
        new Provider("kimi","Kimi / Moonshot","openai-completions","https://api.moonshot.ai/v1",List.of("kimi-k3","kimi-k2.7-code-highspeed","kimi-k2.6")),
        new Provider("openai-compatible","OpenAI 兼容服务","openai-completions","",List.of()));
    public record Profile(String id,String name,String provider,String baseUrl,String model,boolean hasKey){}
    public record Input(String name,String provider,String baseUrl,String model,String apiKey){@Override public String toString(){return "ModelProfileInput[credentials=REDACTED]";}}
    public record Catalog(List<Profile> profiles,String defaultModelId,List<Provider> providers){}
    public record Snapshot(String profileId,String name,String provider,String baseUrl,String model,String api){}
    /** Never serialize or stringify a resolved credential into history, traces or exceptions. */
    public static final class Resolved {
        private final Snapshot snapshot;private final String apiKey;private final boolean allowPrivate;
        public Resolved(Snapshot snapshot,String apiKey,boolean allowPrivate){this.snapshot=snapshot;this.apiKey=apiKey;this.allowPrivate=allowPrivate;}
        public Snapshot snapshot(){return snapshot;}
        @JsonIgnore public String apiKey(){return apiKey;}
        @JsonIgnore public boolean allowPrivate(){return allowPrivate;}
        @Override public String toString(){return "ResolvedModel["+snapshot.profileId()+", credentials=REDACTED]";}
    }
    public static class Stored {public String id,name,provider,baseUrl,model,ciphertext;public Stored(){}}
    public static class Store {public String defaultModelId=SYSTEM;public List<Stored> profiles=new ArrayList<>();}
    private final ObjectMapper json;private final Path root;private final byte[] master;private final SecureRandom random=new SecureRandom();
    public ModelProfileService(ObjectMapper json,@Value("${app.models.dir:tmp/model-profiles}") String directory,@Value("${app.models.master-key:${MODEL_PROFILE_MASTER_KEY:}}") String supplied)throws IOException {
        this.json=json;this.root=Path.of(directory).toAbsolutePath().normalize();privateDirectory(root);
        if(supplied!=null&&!supplied.isBlank()){try{master=Base64.getDecoder().decode(supplied.strip());}catch(IllegalArgumentException e){throw new IOException("Model profile encryption key must be base64");}if(master.length!=32)throw new IOException("Model profile encryption key must contain 32 bytes");}
        else {Path key=root.resolve("master.key");if(!Files.exists(key)){try(var files=Files.walk(root,2)){if(files.anyMatch(p->p.getFileName().toString().equals("profiles.json")))throw new IOException("Model encryption master key is missing; restore the original key");}byte[] fresh=new byte[32];random.nextBytes(fresh);try{Files.write(key,fresh,StandardOpenOption.CREATE_NEW);privateFile(key);}catch(FileAlreadyExistsException ignored){}}
            if(Files.isSymbolicLink(key))throw new IOException("Invalid model encryption key file");master=Files.readAllBytes(key);if(master.length!=32)throw new IOException("Invalid model encryption key file");privateFile(key);}
    }
    public synchronized Catalog catalog()throws IOException {Store s=read();return new Catalog(s.profiles.stream().map(this::publicProfile).toList(),s.defaultModelId,PROVIDERS);}
    public synchronized Profile create(Input input)throws IOException {Store s=read();if(s.profiles.size()>=20)throw new IllegalStateException("最多保存20个个人模型");Stored p=validated(UUID.randomUUID().toString(),input,null);s.profiles.add(p);save(s);return publicProfile(p);}
    public synchronized Profile update(String id,Input input)throws IOException {Store s=read();Stored old=find(s,id);Stored p=validated(id,input,old);s.profiles.set(s.profiles.indexOf(old),p);save(s);return publicProfile(p);}
    public synchronized void delete(String id)throws IOException {Store s=read();Stored p=find(s,id);s.profiles.remove(p);if(id.equals(s.defaultModelId))s.defaultModelId=SYSTEM;save(s);}
    public synchronized Catalog defaults(String id)throws IOException {Store s=read();if(!SYSTEM.equals(id))find(s,id);s.defaultModelId=id;save(s);return catalog();}
    public synchronized String selectedId(String requested)throws IOException {return requested==null||requested.isBlank()?read().defaultModelId:requested;}
    public synchronized Resolved resolve(String requested)throws IOException {
        String selected=selectedId(requested);if(SYSTEM.equals(selected))return null;Stored p=find(read(),selected);boolean privateAllowed=UserContext.current().admin();String url=ModelEndpointPolicy.normalize(p.baseUrl,privateAllowed);
        return new Resolved(new Snapshot(p.id,p.name,p.provider,url,p.model,provider(p.provider).api()),decrypt(p.id,p.ciphertext),privateAllowed);
    }
    private Stored validated(String id,Input input,Stored previous) {
        if(input==null)throw new IllegalArgumentException("缺少模型配置");Provider provider=provider(input.provider());
        String name=bounded(input.name(),80,"请输入1–80字模型名称"),model=bounded(input.model(),200,"请输入模型ID");
        String url=ModelEndpointPolicy.normalize(input.baseUrl(),UserContext.current().admin());
        String secret=input.apiKey();if(secret==null||secret.isBlank()){if(previous==null)throw new IllegalArgumentException("请输入 API Key");secret=decrypt(previous.id,previous.ciphertext);}else{secret=secret.strip();if(secret.length()>8192||secret.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("API Key 格式无效");}
        Stored p=new Stored();p.id=id;p.name=name;p.provider=provider.id();p.baseUrl=url;p.model=model;p.ciphertext=encrypt(id,secret);return p;
    }
    private static String bounded(String value,int max,String error){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException(error);return value.strip();}
    private static Provider provider(String id){return PROVIDERS.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("不支持的模型厂商"));}
    private Profile publicProfile(Stored p){return new Profile(p.id,p.name,p.provider,p.baseUrl,p.model,p.ciphertext!=null&&!p.ciphertext.isBlank());}
    private static Stored find(Store s,String id){if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("模型配置不存在");return s.profiles.stream().filter(p->p.id.equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("模型配置不存在"));}
    private Path file()throws IOException {Path owner=root.resolve(UserContext.current().id());privateDirectory(owner);return owner.resolve("profiles.json");}
    private Store read()throws IOException {Path file=file();if(Files.isSymbolicLink(file))throw new IOException("Invalid model profile store");return Files.isRegularFile(file)?json.readValue(file.toFile(),Store.class):new Store();}
    private void save(Store s)throws IOException {Path file=file(),temp=Files.createTempFile(file.getParent(),".models-",".tmp");try{privateFile(temp);json.writeValue(temp.toFile(),s);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}privateFile(file);}finally{Files.deleteIfExists(temp);}}
    private byte[] aad(String id){return (UserContext.current().id()+":"+id).getBytes(StandardCharsets.UTF_8);}
    private String encrypt(String id,String secret){try{byte[] iv=new byte[12];random.nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(aad(id));byte[] encrypted=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));byte[] both=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,both,0,iv.length);System.arraycopy(encrypted,0,both,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(both);}catch(Exception e){throw new IllegalStateException("模型密钥加密失败");}}
    private String decrypt(String id,String encrypted){try{byte[] both=Base64.getDecoder().decode(encrypted);if(both.length<29)throw new IllegalArgumentException();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,Arrays.copyOf(both,12)));cipher.updateAAD(aad(id));return new String(cipher.doFinal(Arrays.copyOfRange(both,12,both.length)),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("模型密钥无法解密，请检查服务器主密钥或重新配置该模型");}}
    private static void privateDirectory(Path path)throws IOException {if(Files.isSymbolicLink(path))throw new IOException("Invalid model directory");Files.createDirectories(path);try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}}
    private static void privateFile(Path path)throws IOException {try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
}

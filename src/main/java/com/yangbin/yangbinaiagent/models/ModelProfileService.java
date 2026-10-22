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
    public record Profile(String id,String name,String provider,String baseUrl,String model,boolean hasKey,String connectionId,List<String> reasoningEfforts,boolean codingSupported,String codingUnavailableReason){
        public Profile(String id,String name,String provider,String baseUrl,String model,boolean hasKey){this(id,name,provider,baseUrl,model,hasKey,null,ModelReasoning.supported(provider,model,null),ModelReasoning.codingSupported(provider,model),ModelReasoning.codingSupported(provider,model)?null:"当前接口不支持该模型的 Coding 工具调用");}
    }
    public record Connection(String id,String name,String provider,String baseUrl,boolean hasKey){}
    public record ConnectionInput(String name,String provider,String baseUrl,String apiKey){@Override public String toString(){return "ModelConnectionInput[credentials=REDACTED]";}}
    public record Input(String name,String provider,String baseUrl,String model,String apiKey,String connectionId){public Input(String name,String provider,String baseUrl,String model,String apiKey){this(name,provider,baseUrl,model,apiKey,null);}@Override public String toString(){return "ModelProfileInput[credentials=REDACTED]";}}
    public record Catalog(List<Profile> profiles,String defaultModelId,List<Provider> providers,List<Profile> allProfiles,List<Connection> connections,boolean systemEnabled){
        public Catalog(List<Profile> profiles,String defaultModelId,List<Provider> providers){this(profiles,defaultModelId,providers,profiles,List.of(),true);}
    }
    public record Snapshot(String profileId,String name,String provider,String baseUrl,String model,String api,String reasoningEffort){
        public Snapshot(String profileId,String name,String provider,String baseUrl,String model,String api){this(profileId,name,provider,baseUrl,model,api,null);}
    }
    /** Never serialize or stringify a resolved credential into history, traces or exceptions. */
    public static final class Resolved {
        private final Snapshot snapshot;private final String apiKey;private final boolean allowPrivate;
        public Resolved(Snapshot snapshot,String apiKey,boolean allowPrivate){this.snapshot=snapshot;this.apiKey=apiKey;this.allowPrivate=allowPrivate;}
        public Snapshot snapshot(){return snapshot;}
        @JsonIgnore public String apiKey(){return apiKey;}
        @JsonIgnore public boolean allowPrivate(){return allowPrivate;}
        @Override public String toString(){return "ResolvedModel["+snapshot.profileId()+", credentials=REDACTED]";}
    }
    public static class Stored {public String id,name,provider,baseUrl,model,ciphertext,connectionId;public List<String> reasoningEfforts;public boolean discovered,unavailable;public Stored(){}}
    public static class StoredConnection {public String id,name,provider,baseUrl,ciphertext;}
    public static class Store {public String defaultModelId=SYSTEM;public List<Stored> profiles=new ArrayList<>();public List<StoredConnection> connections=new ArrayList<>();public List<String> enabledModelIds;}
    private static final int MAX_MODELS=1000,MAX_CONNECTIONS=20;
    private final java.util.concurrent.Semaphore discoveries=new java.util.concurrent.Semaphore(4);
    private final ObjectMapper json;private final Path root;private final byte[] master;private final SecureRandom random=new SecureRandom();
    public ModelProfileService(ObjectMapper json,@Value("${app.models.dir:tmp/model-profiles}") String directory,@Value("${app.models.master-key:${MODEL_PROFILE_MASTER_KEY:}}") String supplied)throws IOException {
        this.json=json;this.root=Path.of(directory).toAbsolutePath().normalize();privateDirectory(root);
        if(supplied!=null&&!supplied.isBlank()){try{master=Base64.getDecoder().decode(supplied.strip());}catch(IllegalArgumentException e){throw new IOException("Model profile encryption key must be base64");}if(master.length!=32)throw new IOException("Model profile encryption key must contain 32 bytes");}
        else {Path key=root.resolve("master.key");if(!Files.exists(key)){try(var files=Files.walk(root,2)){if(files.anyMatch(p->p.getFileName().toString().equals("profiles.json")))throw new IOException("Model encryption master key is missing; restore the original key");}byte[] fresh=new byte[32];random.nextBytes(fresh);try{Files.write(key,fresh,StandardOpenOption.CREATE_NEW);privateFile(key);}catch(FileAlreadyExistsException ignored){}}
            if(Files.isSymbolicLink(key))throw new IOException("Invalid model encryption key file");master=Files.readAllBytes(key);if(master.length!=32)throw new IOException("Invalid model encryption key file");privateFile(key);}
    }
    public synchronized Catalog catalog()throws IOException {return catalog(read());}
    private Catalog catalog(Store s){
        List<Profile> all=s.profiles.stream().filter(p->available(s,p)).map(p->publicProfile(s,p)).toList();
        return new Catalog(all.stream().filter(p->enabled(s,p.id())).toList(),s.defaultModelId,PROVIDERS,all,s.connections.stream().map(this::publicConnection).toList(),enabled(s,SYSTEM));
    }
    public synchronized Profile create(Input input)throws IOException {Store s=read();if(s.profiles.size()>=MAX_MODELS)throw new IllegalStateException("模型配置数量已达上限");Stored p=validated(s,UUID.randomUUID().toString(),input,null);s.profiles.add(p);save(s);return publicProfile(s,p);}
    public synchronized Profile update(String id,Input input)throws IOException {Store s=read();Stored old=find(s,id);Stored p=validated(s,id,input,old);s.profiles.set(s.profiles.indexOf(old),p);save(s);return publicProfile(s,p);}
    public synchronized void delete(String id)throws IOException {Store s=read();Stored p=find(s,id);s.profiles.remove(p);if(s.enabledModelIds!=null)s.enabledModelIds.remove(id);repairDefault(s);save(s);}
    public synchronized Catalog defaults(String id)throws IOException {Store s=read();checkEnabled(s,id);s.defaultModelId=id;save(s);return catalog(s);}
    public synchronized Catalog selection(List<String> ids)throws IOException {
        Store s=read();if(ids==null||ids.isEmpty()||ids.size()>MAX_MODELS+1)throw new IllegalArgumentException("请至少选择一个模型");
        var unique=new LinkedHashSet<String>();for(String id:ids){if(!SYSTEM.equals(id)&&!available(s,find(s,id)))throw new IllegalArgumentException("模型连接不存在");unique.add(id);}
        s.enabledModelIds=new ArrayList<>(unique);repairDefault(s);save(s);return catalog(s);
    }
    public synchronized String selectedId(String requested)throws IOException {Store s=read();String id=requested==null||requested.isBlank()?s.defaultModelId:requested;checkEnabled(s,id);return id;}
    public synchronized Resolved resolve(String requested)throws IOException {return resolve(requested,null);}
    public synchronized Resolved resolve(String requested,String effort)throws IOException {
        Store s=read();String selected=requested==null||requested.isBlank()?s.defaultModelId:requested;checkEnabled(s,selected);
        if(SYSTEM.equals(selected)){ModelReasoning.validate(effort,List.of());return null;}
        Stored p=find(s,selected);String actualEffort=ModelReasoning.validate(effort,efforts(p));boolean privateAllowed=UserContext.current().admin();
        StoredConnection c=p.connectionId==null?null:connection(s,p.connectionId);String url=ModelEndpointPolicy.normalize(c==null?p.baseUrl:c.baseUrl,privateAllowed);
        return new Resolved(new Snapshot(p.id,p.name,p.provider,url,p.model,provider(p.provider).api(),actualEffort),c==null?decrypt(p.id,p.ciphertext):decrypt(c.id,c.ciphertext),privateAllowed);
    }
    public synchronized Catalog createConnection(ConnectionInput input)throws IOException {
        Store s=read();if(s.connections.size()>=MAX_CONNECTIONS)throw new IllegalStateException("最多保存20个服务连接");
        freezeSelection(s);s.connections.add(validatedConnection(UUID.randomUUID().toString(),input,null));save(s);return catalog(s);
    }
    public synchronized Catalog updateConnection(String id,ConnectionInput input)throws IOException {
        Store s=read();StoredConnection old=connection(s,id),updated=validatedConnection(id,input,old);s.connections.set(s.connections.indexOf(old),updated);
        for(Stored p:s.profiles)if(id.equals(p.connectionId)){boolean changed=!Objects.equals(p.provider,updated.provider)||!Objects.equals(p.baseUrl,updated.baseUrl);p.provider=updated.provider;p.baseUrl=updated.baseUrl;if(changed)p.reasoningEfforts=null;}
        save(s);return catalog(s);
    }
    public synchronized Catalog deleteConnection(String id)throws IOException {
        Store s=read();StoredConnection c=connection(s,id);freezeSelection(s);s.connections.remove(c);
        s.enabledModelIds.removeIf(selected->s.profiles.stream().anyMatch(p->p.id.equals(selected)&&id.equals(p.connectionId)));repairDefault(s);save(s);return catalog(s);
    }
    public Catalog discover(String id)throws IOException {
        if(!discoveries.tryAcquire())throw new IllegalStateException("正在获取模型列表，请稍后重试");
        try{
            final StoredConnection snapshot;final String key;final boolean allowPrivate;
            synchronized(this){snapshot=connection(read(),id);key=decrypt(snapshot.id,snapshot.ciphertext);allowPrivate=UserContext.current().admin();}
            List<ModelDiscovery.Discovered> discovered=ModelDiscovery.fetch(json,snapshot.provider,snapshot.baseUrl,key,allowPrivate);
            synchronized(this){Store s=read();StoredConnection c=connection(s,id);
                if(!Objects.equals(c.ciphertext,snapshot.ciphertext)||!Objects.equals(c.provider,snapshot.provider)||!Objects.equals(c.baseUrl,snapshot.baseUrl))throw new IllegalStateException("服务连接已修改，请重新获取模型");
                freezeSelection(s);
                for(Stored p:s.profiles)if(id.equals(p.connectionId)&&p.discovered)p.unavailable=true;
                for(var item:discovered){Stored p=s.profiles.stream().filter(x->id.equals(x.connectionId)&&item.id().equals(x.model)).findFirst().orElse(null);
                    if(p==null){if(s.profiles.size()>=MAX_MODELS)throw new IllegalArgumentException("模型列表超过保存上限");p=new Stored();p.id=UUID.randomUUID().toString();p.connectionId=id;p.model=item.id();p.name=item.name();s.profiles.add(p);}
                    p.provider=c.provider;p.baseUrl=c.baseUrl;p.reasoningEfforts=item.reasoningEfforts();p.discovered=true;p.unavailable=false;
                }s.enabledModelIds.removeIf(selected->!SYSTEM.equals(selected)&&s.profiles.stream().noneMatch(p->p.id.equals(selected)&&available(s,p)));repairDefault(s);save(s);return catalog(s);
            }
        }finally{discoveries.release();}
    }
    private StoredConnection validatedConnection(String id,ConnectionInput in,StoredConnection previous){
        if(in==null)throw new IllegalArgumentException("缺少服务连接");StoredConnection c=new StoredConnection();c.id=id;c.name=bounded(in.name(),80,"请输入1–80字连接名称");c.provider=provider(in.provider()).id();c.baseUrl=ModelEndpointPolicy.normalize(in.baseUrl(),UserContext.current().admin());
        String key=in.apiKey();if(key==null||key.isBlank()){if(previous==null)throw new IllegalArgumentException("请输入 API Key");key=decrypt(id,previous.ciphertext);}else key=bounded(key,8192,"API Key 格式无效");c.ciphertext=encrypt(id,key);return c;
    }
    private static StoredConnection connection(Store s,String id){return s.connections.stream().filter(c->Objects.equals(c.id,id)).findFirst().orElseThrow(()->new IllegalArgumentException("服务连接不存在"));}
    private Connection publicConnection(StoredConnection c){return new Connection(c.id,c.name,c.provider,c.baseUrl,c.ciphertext!=null&&!c.ciphertext.isBlank());}
    private static boolean enabled(Store s,String id){return s.enabledModelIds==null||s.enabledModelIds.contains(id);}
    private static boolean available(Store s,Stored p){return !p.unavailable&&(p.connectionId==null||s.connections.stream().anyMatch(c->c.id.equals(p.connectionId)));}
    private static void checkEnabled(Store s,String id){if(!SYSTEM.equals(id)&&!available(s,find(s,id)))throw new IllegalArgumentException("模型连接不存在");if(!enabled(s,id))throw new IllegalArgumentException("该模型未在总设置中启用");}
    private static void freezeSelection(Store s){if(s.enabledModelIds==null){s.enabledModelIds=new ArrayList<>();s.enabledModelIds.add(SYSTEM);s.profiles.stream().filter(p->available(s,p)).forEach(p->s.enabledModelIds.add(p.id));}}
    private static void repairDefault(Store s){if(s.enabledModelIds==null){if(!SYSTEM.equals(s.defaultModelId)&&s.profiles.stream().noneMatch(p->p.id.equals(s.defaultModelId)&&available(s,p)))s.defaultModelId=SYSTEM;return;}
        if(s.enabledModelIds.isEmpty()){s.defaultModelId=null;return;}if(!s.enabledModelIds.contains(s.defaultModelId))s.defaultModelId=s.enabledModelIds.get(0);
    }
    private static List<String> efforts(Stored p){return p.reasoningEfforts==null?ModelReasoning.supported(p.provider,p.model,null):p.reasoningEfforts;}
    private Stored validated(Store s,String id,Input input,Stored previous) {
        if(input!=null&&input.connectionId()!=null&&!input.connectionId().isBlank()){
            StoredConnection c=connection(s,input.connectionId());Stored p=new Stored();p.id=id;p.name=bounded(input.name(),80,"请输入模型名称");p.model=bounded(input.model(),200,"请输入模型ID");p.connectionId=c.id;p.provider=c.provider;p.baseUrl=c.baseUrl;
            if(previous!=null&&Objects.equals(previous.connectionId,p.connectionId)&&Objects.equals(previous.model,p.model))p.reasoningEfforts=previous.reasoningEfforts;return p;
        }
        if(previous!=null&&previous.connectionId!=null)throw new IllegalArgumentException("请使用模型所属服务连接进行修改");
        if(input==null)throw new IllegalArgumentException("缺少模型配置");Provider provider=provider(input.provider());
        String name=bounded(input.name(),80,"请输入1–80字模型名称"),model=bounded(input.model(),200,"请输入模型ID");
        String url=ModelEndpointPolicy.normalize(input.baseUrl(),UserContext.current().admin());
        String secret=input.apiKey();if(secret==null||secret.isBlank()){if(previous==null)throw new IllegalArgumentException("请输入 API Key");secret=decrypt(previous.id,previous.ciphertext);}else{secret=secret.strip();if(secret.length()>8192||secret.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("API Key 格式无效");}
        Stored p=new Stored();p.id=id;p.name=name;p.provider=provider.id();p.baseUrl=url;p.model=model;p.ciphertext=encrypt(id,secret);return p;
    }
    private static String bounded(String value,int max,String error){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException(error);return value.strip();}
    private static Provider provider(String id){return PROVIDERS.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("不支持的模型厂商"));}
    private Profile publicProfile(Store s,Stored p){return new Profile(p.id,p.name,p.provider,p.baseUrl,p.model,p.connectionId!=null?connection(s,p.connectionId).ciphertext!=null:p.ciphertext!=null&&!p.ciphertext.isBlank(),p.connectionId,efforts(p),ModelReasoning.codingSupported(p.provider,p.model),ModelReasoning.codingSupported(p.provider,p.model)?null:"当前接口不支持该模型的 Coding 工具调用");}
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

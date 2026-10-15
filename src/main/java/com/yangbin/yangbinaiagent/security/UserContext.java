package com.yangbin.yangbinaiagent.security;
import java.util.function.Supplier;
/** Identity set only after the proxy token is verified; async work must capture it explicitly. */
public final class UserContext {
 public record User(String id,String role){
  public User {if(!validId(id)||!(role.equals("admin")||role.equals("user"))||role.equals("admin")!=id.equals("admin"))throw new IllegalArgumentException("Invalid user identity");}
  public boolean admin(){return role.equals("admin");}
 }
 public static final User ADMIN=new User("admin","admin");
 private static final ThreadLocal<User> CURRENT=new ThreadLocal<>();
 private UserContext(){}
 public static boolean validId(String id){return id!=null&&(id.equals("admin")||id.matches("[a-f0-9]{32}"));}
 public static User current(){var user=CURRENT.get();if(user==null)throw new IllegalStateException("Missing authenticated user context");return user;}
 public static Scope open(User user){User previous=CURRENT.get();CURRENT.set(user);return ()->{if(previous==null)CURRENT.remove();else CURRENT.set(previous);};}
 public interface Scope extends AutoCloseable {void close();}
 public static <T>T call(User user,Supplier<T> action){try(var ignored=open(user)){return action.get();}}
 public static String chatId(String id){if(id==null||id.isBlank()||id.length()>100)throw new IllegalArgumentException("Invalid conversation ID");return current().id()+":"+id;}
}

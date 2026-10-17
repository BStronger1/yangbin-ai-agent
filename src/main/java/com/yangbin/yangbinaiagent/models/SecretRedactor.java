package com.yangbin.yangbinaiagent.models;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
/** Scrubs literal and common serialized forms before responses enter tool traces or history. */
public final class SecretRedactor {
    private static final ObjectMapper JSON=new ObjectMapper();
    private SecretRedactor(){}
    public static String redact(String value,String key){
        if(value==null)return "";if(key==null||key.isEmpty())return value;
        String result=value;
        try{String escaped=JSON.writeValueAsString(key);result=result.replace(escaped.substring(1,escaped.length()-1),"[REDACTED]");}catch(Exception ignored){}
        result=result.replace(URLEncoder.encode(key,StandardCharsets.UTF_8),"[REDACTED]");
        return result.replace(key,"[REDACTED]");
    }
}

package com.yangbin.yangbinaiagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Bounded validator for the JSON Schema subset used by manual MCP tools. */
public final class McpArguments {
 private McpArguments(){}
 public static void validate(JsonNode schema,JsonNode value){
  if(value==null||!value.isObject()||value.toString().length()>8000)throw new IllegalArgumentException("参数必须是 JSON 对象，最多8000字符");
  check(schema,value,"参数",0);
 }
 private static void check(JsonNode schema,JsonNode value,String path,int depth){
  if(depth>8||schema.has("$ref")||schema.has("oneOf")||schema.has("anyOf")||schema.has("allOf"))throw new IllegalArgumentException("此参数定义暂不支持表单调用");
  String type=schema.path("type").asText("");
  boolean valid=switch(type){case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isTextual();case "integer"->value.isIntegralNumber();case "number"->value.isNumber();case "boolean"->value.isBoolean();case "null"->value.isNull();default->false;};
  if(!valid)throw new IllegalArgumentException(path+" 类型不匹配："+type);
  if(schema.has("enum")){boolean match=false;for(var option:schema.get("enum"))match|=option.equals(value);if(!match)throw new IllegalArgumentException(path+" 不在允许的选项中");}
  if(value.isObject()){
   for(var required:schema.path("required"))if(!value.has(required.asText()))throw new IllegalArgumentException("缺少参数："+required.asText());
   var fields=value.fields();while(fields.hasNext()){var f=fields.next();if(!schema.path("properties").has(f.getKey()))throw new IllegalArgumentException("未知参数："+f.getKey());check(schema.path("properties").get(f.getKey()),f.getValue(),path+"."+f.getKey(),depth+1);}
  }else if(value.isArray()){
   if(value.size()>100||value.size()<schema.path("minItems").asInt(0)||value.size()>schema.path("maxItems").asInt(100))throw new IllegalArgumentException(path+" 数组长度不符合要求");
   for(var child:value)check(schema.path("items"),child,path+"[]",depth+1);
  }else if(value.isTextual()){
   int n=value.asText().length();if(n<schema.path("minLength").asInt(0)||n>schema.path("maxLength").asInt(8000))throw new IllegalArgumentException(path+" 文本长度不符合要求");
  }else if(value.isNumber()){
   if(schema.has("minimum")&&value.decimalValue().compareTo(schema.get("minimum").decimalValue())<0||schema.has("maximum")&&value.decimalValue().compareTo(schema.get("maximum").decimalValue())>0)throw new IllegalArgumentException(path+" 数值超出范围");
  }
 }
}

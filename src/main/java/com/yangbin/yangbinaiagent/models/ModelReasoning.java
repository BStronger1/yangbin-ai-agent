package com.yangbin.yangbinaiagent.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Provider-native effort, never a prompt substitute. Unknown models stay on provider default.
 * Sources: OpenAI reasoning guide, DeepSeek thinking_mode/list-models, Anthropic effort/models.
 * Static fallbacks are conservative; discovery metadata takes precedence where documented. */
public final class ModelReasoning {
    private ModelReasoning(){}
    private static final List<String> ORDER=List.of("none","minimal","low","medium","high","xhigh","max","ultra");
    public static List<String> supported(String provider,String model,JsonNode metadata){
        String id=Objects.toString(model,"").toLowerCase(Locale.ROOT);
        if("deepseek".equals(provider) || "openai-compatible".equals(provider) && id.matches("deepseek-(?:flash|v4(?:\\.1)?-(?:flash|pro)(?:-[a-z0-9.-]+)?)")){
            JsonNode levels=metadata==null?null:metadata.path("effort").path("supported_levels");
            if(levels!=null&&levels.isArray()){
                var result=new ArrayList<String>();result.add("none");for(JsonNode v:levels)if(List.of("low","high","max").contains(v.asText())&&!result.contains(v.asText()))result.add(v.asText());return List.copyOf(result);
            }
            if(id.equals("deepseek-flash")||id.matches("deepseek-v4(?:\\.1)?-(?:flash|pro)(?:-[a-z0-9.-]+)?"))return List.of("none","low","high","max");
        }
        if("anthropic".equals(provider)){
            JsonNode effort=metadata==null?null:metadata.path("capabilities").path("effort");
            if(effort!=null&&effort.path("supported").isBoolean()&&!effort.path("supported").asBoolean())return List.of();
            if(effort!=null&&!effort.isMissingNode())return ORDER.stream().filter(v->List.of("low","medium","high","xhigh","max").contains(v)&&effort.path(v).path("supported").asBoolean(false)).toList();
            if(id.matches("claude-opus-4-5(-[0-9]{8})?"))return List.of("low","medium","high");
            if(id.matches("claude-(opus|sonnet)-4-6(-[0-9]{8})?"))return List.of("low","medium","high","max");
        }
        if("openai".equals(provider)){
            if(id.matches("(o1|o3|o3-mini|o4-mini)(-[0-9]{4}-[0-9]{2}-[0-9]{2})?"))return List.of("low","medium","high");
            if(id.matches("gpt-5(-mini|-nano)?(-[0-9]{4}-[0-9]{2}-[0-9]{2})?"))return List.of("minimal","low","medium","high");
            if(id.matches("gpt-5\\.1(-[0-9]{4}-[0-9]{2}-[0-9]{2})?"))return List.of("none","low","medium","high");
            if(id.matches("gpt-5\\.(2|4|5)(-[0-9]{4}-[0-9]{2}-[0-9]{2})?"))return List.of("none","low","medium","high","xhigh");
        }
        return List.of();
    }
    public static String validate(String effort,List<String> supported){
        if(effort==null||effort.isBlank()||"default".equals(effort))return null;
        if(!supported.contains(effort))throw new IllegalArgumentException("该模型不支持所选推理强度，请重新选择");return effort;
    }
    public static boolean codingSupported(String provider,String model){return !("openai".equals(provider)&&Objects.toString(model,"").matches("gpt-(6-astra|6\\.1-sol)(-.*)?"));}
    public static void apply(ObjectNode payload,ModelProfileService.Snapshot snapshot){
        String effort=snapshot.reasoningEffort();if(effort==null||effort.isBlank())return;
        switch("openai-compatible".equals(snapshot.provider()) && !supported(snapshot.provider(),snapshot.model(),null).isEmpty() ? "deepseek" : snapshot.provider()){
            case "deepseek" -> {payload.putObject("thinking").put("type","none".equals(effort)?"disabled":"enabled");if("none".equals(effort))payload.remove("reasoning_effort");else payload.put("reasoning_effort",effort);}
            case "anthropic" -> {ObjectNode output=payload.path("output_config") instanceof ObjectNode o?o:payload.putObject("output_config");output.put("effort",effort);}
            case "openai" -> payload.put("reasoning_effort",effort);
            default -> throw new IllegalArgumentException("该模型不支持推理强度设置");
        }
    }
}

package com.yangbin.yangbinaiagent.memory;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded rule extraction, not semantic understanding. Never reads assistant/tool history. */
public final class MemoryCandidateExtractor {
 public record Proposal(String key, String content, String kind, String uncertainty) {}
 private static final Pattern FACT = Pattern.compile("^(?:请记住[，,:：]?|记住[，,:：]?|我们项目的|本项目的)([^，。！？:：]{1,60}?)(?:是|为|改为|改成)([^。！？]{1,1000})[。]?$" );
 private static final Pattern PREFERENCE = Pattern.compile("^(?:请记住[，,:：]?)?我(?:更喜欢|偏好|希望以后)([^。！？]{1,1000})[。]?$" );
 private static final Pattern UNSURE = Pattern.compile("(?i)(?:也许|可能|猜测|假设|如果|举例|例如|据说|不确定|不要记|不用记|不再|并非|不是|听说|工具结果|模型认为|assistant|system|tool|```|[“”\"<>])");
 private MemoryCandidateExtractor() {}
 public static List<Proposal> extract(String text) {
  if (text == null || text.length() > 32000) throw new IllegalArgumentException("候选输入过长");
  var result = new ArrayList<Proposal>(); int lines = 0;
  for (String raw : text.split("\\R")) {
   if (++lines > 100 || result.size() >= 4) break;
   String line=raw.strip(); if(UNSURE.matcher(line).find()) continue;
   var preference=PREFERENCE.matcher(line);
   if(preference.matches()) { result.add(new Proposal("回答偏好",preference.group(1).replaceFirst("。$", "").strip(),"PREFERENCE","RULE_MATCH_REQUIRES_USER_CONFIRMATION")); continue; }
   var fact=FACT.matcher(line);
   if(fact.matches()) result.add(new Proposal(fact.group(1).strip(),fact.group(2).replaceFirst("。$", "").strip(),"FACT","RULE_MATCH_REQUIRES_USER_CONFIRMATION"));
  }
  return List.copyOf(result);
 }
}

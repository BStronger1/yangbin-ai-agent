package com.yangbin.yangbinaiagent.work;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Persisted content contract, independent of any model's prose or PowerPoint renderer. */
public record PresentationDraft(String title, List<PresentationDraft.Slide> slides) {
    public record Slide(String title, List<String> points, String notes) {}
    public static final String INSTRUCTIONS = """
        本技能生成演示内容。只返回JSON对象，不加Markdown围栏：
        {"title":"演示标题","slides":[{"title":"本页标题","points":["要点"],"notes":"本页演讲者讲稿"}]}
        默认8页，用户指定页数优先，最多20页；总页数包含用户需要的封面，不额外添加封面。
        title为1-60字符；每页title为1-40字符；points为0-4条，每条1-80字符；
        每页notes为0-2000字符，将详细论证和口播放入notes，页面只保留精炼要点。
        不伪造数据、来源或成果。缺失事实在要点或讲稿中明确标注待补，不补造图表。
        系统会在保存后按需生成可编辑PPTX；你只负责内容，不声称已生成文件。
        """;
    public static PresentationDraft parse(ObjectMapper json,String raw) throws java.io.IOException {
        if (raw == null || raw.length() > 40000) throw new IllegalArgumentException("演示内容为空或过长");
        String text = raw.strip();
        if (text.startsWith("```json") && text.endsWith("```")) text = text.substring(7,text.length()-3).strip();
        else if (text.startsWith("```") && text.endsWith("```")) text = text.substring(3,text.length()-3).strip();
        PresentationDraft draft = json.readerFor(PresentationDraft.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(text);
        validate(draft); return draft;
    }
    public static void validate(PresentationDraft draft) {
        if (draft == null || !text(draft.title(),1,60) || draft.slides() == null
            || draft.slides().isEmpty() || draft.slides().size() > 20) throw new IllegalArgumentException("演示需包含1–20页");
        for (var slide : draft.slides()) {
            if (slide == null || !text(slide.title(),1,40) || slide.points() == null || slide.points().size() > 4
                || !text(slide.notes(),0,2000) || slide.points().stream().anyMatch(s -> !text(s,1,80)))
                throw new IllegalArgumentException("演示页内容超出排版范围");
        }
    }
    private static boolean text(String s,int min,int max) {
        return s != null && s.strip().length() >= min && s.length() <= max
            && s.codePoints().noneMatch(c -> c < 32 && c != '\n' && c != '\t' && c != '\r');
    }
    public String markdown() {
        var out = new StringBuilder("# ").append(title).append("\n\n");
        for (int i=0;i<slides.size();i++) {
            var s=slides.get(i); out.append("## ").append(i+1).append(". ").append(s.title()).append("\n\n");
            for (String point:s.points()) out.append("- ").append(point).append("\n");
            if (!s.notes().isBlank()) out.append("\n讲稿：\n\n").append(s.notes()).append("\n");
            out.append("\n");
        }
        return out.toString();
    }
}

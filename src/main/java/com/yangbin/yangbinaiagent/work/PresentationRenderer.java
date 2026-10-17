package com.yangbin.yangbinaiagent.work;

import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.sl.usermodel.*;
import org.apache.poi.common.usermodel.fonts.FontGroup;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.util.List;

/** Native editable text slides; never fetches links, images or model-generated code. */
public final class PresentationRenderer {
    public static final String VERSION = "editable-text-pptx-v1";
    private PresentationRenderer() {}
    public static byte[] render(PresentationDraft draft,String fontFamily) throws IOException {
        PresentationDraft.validate(draft);
        if (new Font(fontFamily,Font.PLAIN,22).canDisplayUpTo("中文演示") != -1)
            throw new IllegalStateException("演示字体不可用，请配置已安装的中文字体");
        try (var ppt = new XMLSlideShow(); var bytes = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(960,540));
            ppt.getProperties().getCoreProperties().setTitle(draft.title());
            ppt.getProperties().getCoreProperties().setCreator("yangbin AI");
            for (int i=0;i<draft.slides().size();i++) {
                var data = draft.slides().get(i);
                var slide = ppt.createSlide();
                slide.getBackground().setFillColor(Color.WHITE);
                var title = box(slide,54,38,852,84);
                var heading = title.addNewTextParagraph().addNewTextRun();
                heading.setText(data.title()); heading.setBold(true); heading.setFontColor(new Color(31,49,78));
                for (double size:List.of(32.0,30.0,28.0,26.0,24.0)) {
                    font(heading,fontFamily,size);
                    if (title.getTextHeight() <= 84) break;
                }
                if (title.getTextHeight() > 84) throw new IllegalArgumentException("页标题过长，请缩短后再生成演示");
                if (!data.points().isEmpty()) {
                    var body = box(slide,54,140,852,325);
                    for (String point:data.points()) {
                        var paragraph=body.addNewTextParagraph();
                        paragraph.setBullet(true); paragraph.setIndent(-15.0); paragraph.setLeftMargin(20.0);
                        paragraph.setSpaceAfter(16.0);
                        var run=paragraph.addNewTextRun(); run.setText(point);
                        run.setFontColor(new Color(64,80,102)); font(run,fontFamily,24);
                    }
                    boolean fits=false;
                    for (double size:List.of(24.0,22.0,20.0,18.0)) {
                        for (var paragraph:body.getTextParagraphs()) for (var run:paragraph.getTextRuns()) font(run,fontFamily,size);
                        if (body.getTextHeight() <= 325) { fits=true; break; }
                    }
                    if (!fits) throw new IllegalArgumentException("页面要点过多，请精简内容后再生成演示");
                }
                var footer=box(slide,54,493,852,24);
                var page=footer.addNewTextParagraph(); page.setTextAlign(TextParagraph.TextAlign.RIGHT);
                var number=page.addNewTextRun(); number.setText((i+1)+" / "+draft.slides().size());
                number.setFontColor(new Color(145,155,170)); font(number,fontFamily,11);
                var notes=ppt.getNotesSlide(slide);
                for (var noteShape:notes.getShapes()) if (noteShape instanceof XSLFTextShape shape && shape.getTextType()==Placeholder.BODY) {
                    shape.setText(data.notes());
                    for (var paragraph:shape.getTextParagraphs()) for (var run:paragraph.getTextRuns()) font(run,fontFamily,12);
                }
            }
            ppt.write(bytes); return bytes.toByteArray();
        }
    }
    private static XSLFTextBox box(XSLFSlide slide,double x,double y,double w,double h) {
        var box=slide.createTextBox(); box.clearText(); box.setAnchor(new Rectangle2D.Double(x,y,w,h));
        box.setTopInset(0); box.setBottomInset(0); box.setLeftInset(0); box.setRightInset(0);
        box.setWordWrap(true); box.setTextAutofit(TextShape.TextAutofit.NONE); return box;
    }
    private static void font(XSLFTextRun run,String family,double size) {
        run.setFontFamily(family); run.setFontFamily(family,FontGroup.EAST_ASIAN); run.setFontSize(size);
    }
}

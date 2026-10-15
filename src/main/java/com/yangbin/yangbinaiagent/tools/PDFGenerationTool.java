package com.yangbin.yangbinaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.yangbin.yangbinaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;

/**
 * PDF 生成工具
 */
public class PDFGenerationTool {

    @Tool(description = "Generate a PDF file with given content", returnDirect = false)
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9_-]+[.]pdf")) {
            return "Error: use an ASCII PDF filename without directory components";
        }
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf/" + com.yangbin.yangbinaiagent.security.UserContext.current().id();
        String filePath = fileDir + "/" + fileName;
        try {
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 创建 PdfWriter 和 PdfDocument 对象
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                // 自定义字体（需要人工下载字体文件到特定目录）
//                String fontPath = Paths.get("src/main/resources/static/fonts/simsun.ttf")
//                        .toAbsolutePath().toString();
//                PdfFont font = PdfFontFactory.createFont(fontPath,
//                        PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
                // 使用内置中文字体
                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                document.setFont(font).setFontSize(11);
                PdfFont korean=PdfFontFactory.createFont("HYSMyeongJoStd-Medium","UniKS-UCS2-H");
                PdfFont japanese=PdfFontFactory.createFont("KozMinPro-Regular","UniJIS-UCS2-H");
                // 创建段落
                Paragraph paragraph = new Paragraph().setMultipliedLeading(1.2f);
                // External map names may contain Hangul/Japanese or unsupported symbols.
                // Pick a font with an actual glyph; preserve an explicit code point for unsupported ones.
                StringBuilder run=new StringBuilder();PdfFont current=font;boolean replaced=false;
                for(int offset=0;offset<content.length();){int cp=content.codePointAt(offset);offset+=Character.charCount(cp);
                    PdfFont chosen=cp<32||font.containsGlyph(cp)?font:korean.containsGlyph(cp)?korean:japanese.containsGlyph(cp)?japanese:font;
                    String text=cp<32||chosen.containsGlyph(cp)?new String(Character.toChars(cp)):"[U+"+Integer.toHexString(cp).toUpperCase(java.util.Locale.ROOT)+"]";
                    if(text.startsWith("[U+"))replaced=true;
                    if(chosen!=current&&run.length()>0){paragraph.add(new com.itextpdf.layout.element.Text(run.toString()).setFont(current));run.setLength(0);}current=chosen;run.append(text);
                }
                if(run.length()>0)paragraph.add(new com.itextpdf.layout.element.Text(run.toString()).setFont(current));
                if(replaced)paragraph.add(new com.itextpdf.layout.element.Text("\n部分字符以 Unicode 编号显示，请通过原始来源查看原名。").setFont(font));
                // 添加段落并关闭文档
                document.add(paragraph);
            }
            return "PDF generated successfully. Download: /api/files/" + fileName;
        } catch (IOException e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }
}

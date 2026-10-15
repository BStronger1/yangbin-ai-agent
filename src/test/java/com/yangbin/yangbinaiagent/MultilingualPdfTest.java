package com.yangbin.yangbinaiagent;
import org.junit.jupiter.api.Test;
import com.yangbin.yangbinaiagent.tools.PDFGenerationTool;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.constant.FileConstant;
import java.nio.file.*;import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class MultilingualPdfTest {
 @Test void externalPlaceNamesDoNotBreakPdf()throws Exception{
  String name="multilingual-"+UUID.randomUUID()+".pdf";Path path=Path.of(FileConstant.FILE_SAVE_DIR,"pdf","admin",name);
  try(var scope=UserContext.open(UserContext.ADMIN)){
   assertThat(new PDFGenerationTool().generatePDF(name,"上海 · 비펑탕 · 東京 · Café & Meal MUJI\n天气来源：https://api.open-meteo.com/v1/forecast?latitude=31.22&longitude=121.45\n未知字符测试 🚀")).startsWith("PDF generated successfully");
   try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(path.toString()))){assertThat(pdf.getNumberOfPages()).isGreaterThan(0);String text=com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(1));assertThat(text).contains("上海","비펑탕","[U+1F680]");}
  }finally{Files.deleteIfExists(path);}
 }
}

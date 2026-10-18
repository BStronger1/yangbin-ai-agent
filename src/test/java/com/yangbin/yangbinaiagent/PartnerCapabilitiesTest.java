package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.work.*;
import com.yangbin.yangbinaiagent.models.*;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartnerCapabilitiesTest {
 @TempDir Path root;
 final ObjectMapper json=new ObjectMapper();
 ChatModel model;WorkCatalog catalog;WorkService work;PartnerResources resources;PartnerChatService chat;ModelProfileService profiles;PrivateChatModels factory;UserContext.Scope scope;
 @BeforeEach void setup()throws Exception {
  scope=UserContext.open(TaskWorkflowTest.A);model=mock(ChatModel.class);catalog=new WorkCatalog(json);
  var provenance=new ExecutionProvenance("m020-test","fixture","fake","");work=new WorkService(json,model,catalog,provenance,root.resolve("work").toString());resources=new PartnerResources(json,catalog,root.resolve("work").toString());
  profiles=new ModelProfileService(json,root.resolve("models").toString(),"");factory=mock(PrivateChatModels.class);
  chat=new PartnerChatService(json,model,catalog,work,provenance,root.resolve("work").toString(),resources,profiles,factory);
  when(model.call(any(Prompt.class))).thenReturn(PartnerChatServiceTest.answer("# 任务成果\n\n| 事项 | 状态 |\n| --- | --- |\n| 核对材料 | 待确认 |\n| =HYPERLINK(\"x\") | 文本 |"));clearInvocations(model);
 }
 @AfterEach void close(){chat.close();scope.close();}
 String id(){return UUID.randomUUID().toString();}
 PartnerChatService.Input input(String text,String skill,String profile){return new PartnerChatService.Input(id(),id(),0,text,skill,List.of(),profile);}
 ModelProfileService.Profile profile(String name)throws Exception{return profiles.create(new ModelProfileService.Input(name,"openai","https://8.8.8.8/v1","fixture-model","TEST-KEY-DO-NOT-PERSIST"));}
 @ParameterizedTest @ValueSource(strings={"presentation","marketing","content","video","product","data","office","career"})
 void everyPartnerHasDistinctSkillsAndProducesEditableWordAndLiteralExcel(String partner)throws Exception {
  assertThat(catalog.partner(partner).skillIds()).hasSizeGreaterThanOrEqualTo(3);
  var c=chat.send(partner,input("根据资料交付文稿与行动表","auto","system"));var t=c.turns().getLast();assertThat(t.status()).isEqualTo("SUCCEEDED");assertThat(t.artifacts()).hasSize(3);
  var word=t.artifacts().stream().filter(r->r.name().endsWith(".docx")).findFirst().orElseThrow();
  try(var doc=new XWPFDocument(new ByteArrayInputStream(resources.bytes(partner,word.id())))){assertThat(doc.getParagraphs().stream().map(p->p.getText())).contains("任务成果");assertThat(doc.getTables()).hasSize(1);assertThat(doc.getTables().getFirst().getRow(1).getCell(0).getText()).isEqualTo("核对材料");}
  var excel=t.artifacts().stream().filter(r->r.name().endsWith(".xlsx")).findFirst().orElseThrow();
  try(var wb=new XSSFWorkbook(new ByteArrayInputStream(resources.bytes(partner,excel.id())))){assertThat(wb.getSheet("方案表格1").getRow(2).getCell(0).getCellType()).isEqualTo(CellType.STRING);assertThat(wb.getSheet("方案表格1").getRow(2).getCell(0).getStringCellValue()).startsWith("=HYPERLINK");}
 }
 @Test void explicitFormatWinsAndAutomaticModeDoesNotInjectMeetingTemplate()throws Exception {
  chat.saveProfile("office",new PartnerChatService.Profile(0,"默认写详细会议纪要"));
  var c=chat.send("office",input("只用两句话回答","auto","system"));String instructions=c.turns().getLast().systemInstructions();
  assertThat(instructions).contains("优先服从本轮明确要求","自由对话").doesNotContain("行动项表（事项/负责人/期限/状态）");
  var selected=chat.send("office",input("只输出邮件正文","business-email","system"));assertThat(selected.turns().getLast().systemInstructions()).contains(catalog.skill("business-email").instructions());
 }
 @Test void privateModelIsFrozenAndDuplicateDoesNotResolveDeletedProfileOrCallAgain()throws Exception {
  var profile=profile("个人模型");profiles.defaults(profile.id());var privateModel=mock(ChatModel.class);when(factory.create(any())).thenReturn(privateModel);when(privateModel.call(any(Prompt.class))).thenReturn(PartnerChatServiceTest.answer("个人模型结果"));
  var request=input("任务","auto",profile.id());var result=chat.send("product",request);var t=result.turns().getLast();assertThat(t.modelSnapshot().profileId()).isEqualTo(profile.id());assertThat(t.answer()).isEqualTo("个人模型结果");assertThat(t.attempt().modelProvider()).isEqualTo("openai");assertThat(t.attempt().configuredModel()).isEqualTo("fixture-model");
  profiles.delete(profile.id());assertThat(chat.send("product",request)).isEqualTo(result);verify(privateModel,times(1)).call(any(Prompt.class));verify(model,never()).call(any(Prompt.class));
  assertThat(json.writeValueAsString(result)).doesNotContain("TEST-KEY-DO-NOT-PERSIST","apiKey");
  assertThatThrownBy(()->chat.send("product",new PartnerChatService.Input(request.conversationId(),request.requestId(),0,request.text(),"auto",List.of(),"system"))).hasMessageContaining("其他内容");
 }
 @Test void accountDefaultWorksAndForeignModelCannotReserveTask()throws Exception {
  var profile=profile("默认模型");profiles.defaults(profile.id());var privateModel=mock(ChatModel.class);when(factory.create(any())).thenReturn(privateModel);when(privateModel.call(any(Prompt.class))).thenReturn(PartnerChatServiceTest.answer("默认回复"));
  assertThat(chat.send("career",input("模拟面试","mock-interview",null)).turns().getLast().modelSnapshot().profileId()).isEqualTo(profile.id());
  try(var ignored=UserContext.open(TaskWorkflowTest.B)){assertThatThrownBy(()->chat.send("career",input("测试","auto",profile.id()))).isInstanceOf(IllegalArgumentException.class);assertThat(chat.list("career")).isEmpty();}
 }
 @Test void docxAndTextPdfAreReadAndInvalidOrScannedDocumentsRejected()throws Exception {
  byte[] doc=PartnerDeliverables.word("材料","# 项目事实\n负责人小李，期限周五。");
  var word=resources.upload("office",new MockMultipartFile("file","材料.docx",PartnerDeliverables.DOCX,doc));
  assertThat(resources.analyze("office",List.of(word)).context()).contains("负责人小李，期限周五");
  var bytes=new ByteArrayOutputStream();try(var pdf=new PdfDocument(new PdfWriter(bytes));var document=new Document(pdf)){document.add(new Paragraph("Source: customer interview, 12 participants."));}
  var file=resources.upload("product",new MockMultipartFile("file","research.pdf","application/pdf",bytes.toByteArray()));assertThat(resources.analyze("product",List.of(file)).context()).contains("12 participants");
  assertThatThrownBy(()->resources.upload("office",new MockMultipartFile("file","bad.docx",PartnerDeliverables.DOCX,new byte[]{1,2}))).isInstanceOf(IllegalArgumentException.class);
  var blank=new ByteArrayOutputStream();try(var pdf=new PdfDocument(new PdfWriter(blank))){pdf.addNewPage();}
  assertThatThrownBy(()->resources.upload("office",new MockMultipartFile("file","scan.pdf","application/pdf",blank.toByteArray()))).hasMessageContaining("OCR");
 }
 @Test void generatedDocumentsCanBeReusedOnlyInTheirOwnTaskAndDeletionChecksRevision()throws Exception {
  var c=chat.send("content",input("初稿","auto","system"));var file=c.turns().getLast().artifacts().stream().filter(r->r.name().endsWith(".docx")).findFirst().orElseThrow();
  var updated=chat.send("content",new PartnerChatService.Input(c.id(),id(),1,"修改附件初稿","content-rewrite",List.of(file.id()),"system"));assertThat(updated.turns().getLast().materialContext()).contains("任务成果");
  assertThatThrownBy(()->resources.bind("content",List.of(file.id()),id(),id())).hasMessageContaining("当前任务");
  try(var ignored=UserContext.open(TaskWorkflowTest.B)){assertThatThrownBy(()->chat.deleteConversation("content",c.id(),2)).hasMessage("会话不存在");}
  assertThatThrownBy(()->chat.deleteConversation("content",c.id(),1)).hasMessageContaining("已更新");
  chat.deleteConversation("content",c.id(),2);assertThatThrownBy(()->chat.send("content",new PartnerChatService.Input(c.id(),id(),0,"重新使用旧编号","auto",List.of(),"system"))).hasMessageContaining("已删除");assertThat(chat.list("content")).isEmpty();assertThat(resources.list("content",c.id())).isEmpty();
 }
 @Test void dataQualityNumbersComeFromWholeUploadedTable()throws Exception {
  var file=resources.upload("data",new MockMultipartFile("file","quality.csv","text/csv","名称,数值\n甲,10\n甲,10\n乙,\n".getBytes(StandardCharsets.UTF_8)));
  var context=resources.analyze("data",List.of(file)).context();assertThat(context).contains("重复数据行（全行完全相同）=1","数值：空白数=1，非空数=2","合计=20.0");
 }
 @Test void equalRequestIdsInDifferentConversationsCannotOverwriteResources()throws Exception {
  String request=id();var first=chat.send("office",new PartnerChatService.Input(id(),request,0,"任务一","auto",List.of(),"system"));
  when(model.call(any(Prompt.class))).thenReturn(PartnerChatServiceTest.answer("任务二文稿"));
  var second=chat.send("office",new PartnerChatService.Input(id(),request,0,"任务二","auto",List.of(),"system"));
  assertThat(first.turns().getFirst().artifacts().stream().map(PartnerResources.Resource::id)).doesNotContainAnyElementsOf(second.turns().getFirst().artifacts().stream().map(PartnerResources.Resource::id).toList());
  assertThat(new String(resources.bytes("office",first.turns().getFirst().artifacts().getFirst().id()),StandardCharsets.UTF_8)).contains("任务成果");
 }
 @Test void codeBlocksAreNotTurnedIntoPlanTablesAndEscapedPipesArePreserved() {
  assertThat(PartnerDeliverables.tables("```\n|a|b|\n|---|---|\n|1|2|\n```\n")).isEmpty();
  assertThat(PartnerDeliverables.tables("|a|b|\n|---|---|\n|x\\|y|2|").getFirst().rows().get(1).getFirst()).isEqualTo("x|y");
 }
}

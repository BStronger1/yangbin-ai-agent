package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.work.*;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PartnerWorkspaceTest {
 @TempDir Path root; final ObjectMapper json=new ObjectMapper(); UserContext.Scope scope;PartnerResources resources;PartnerChatService chat;ChatModel model;WorkCatalog catalog;
 @BeforeEach void setup()throws Exception{scope=UserContext.open(TaskWorkflowTest.A);model=mock(ChatModel.class);catalog=new WorkCatalog(json);var p=new ExecutionProvenance("m014-test","mock","test","");var w=new WorkService(json,model,catalog,p,root.toString());resources=new PartnerResources(json,catalog,root.toString());chat=new PartnerChatService(json,model,catalog,w,p,root.toString(),resources);when(model.call(any(Prompt.class))).thenReturn(PartnerChatServiceTest.answer("统计结论"));}
 @AfterEach void close(){chat.close();scope.close();}
 String id(){return UUID.randomUUID().toString();}
 PartnerResources.Resource upload(String name,String text)throws Exception{return resources.upload("office",new MockMultipartFile("file",name,"text/csv",text.getBytes(StandardCharsets.UTF_8)));}
 @Test void csvProducesRecalculatedWorkbookChartAndLiteralFormula()throws Exception{
  var file=upload("销售.csv","名称,金额,备注\n\"甲,店\",10,=HYPERLINK(\"x\")\n乙,20,正常\n丙,30,正常");String c=id(),t=id();
  var input=new PartnerChatService.Input(c,t,0,"分析材料",catalog.partner("office").skillId(),List.of(file.id()));var result=chat.send("office",input);var turn=result.turns().getLast();assertThat(turn.status()).isEqualTo("SUCCEEDED");assertThat(turn.materialContext()).contains("合计=60.0","平均=20.0","甲,店");assertThat(turn.events()).hasSize(5);assertThat(turn.artifacts()).hasSize(4);
  var excel=turn.artifacts().stream().filter(r->r.name().endsWith("xlsx")).findFirst().orElseThrow();try(var wb=new XSSFWorkbook(new ByteArrayInputStream(resources.bytes("office",excel.id())))){assertThat(wb.getSheet("数值统计").getRow(1).getCell(3).getNumericCellValue()).isEqualTo(60);assertThat(wb.getSheet("数值统计").getRow(1).getCell(3).getCellType()).isEqualTo(CellType.FORMULA);assertThat(wb.getSheet("原始数据1").getRow(1).getCell(2).getCellType()).isEqualTo(CellType.STRING);}
  var png=turn.artifacts().stream().filter(r->r.mime().equals("image/png")).findFirst().orElseThrow();assertThat(javax.imageio.ImageIO.read(new ByteArrayInputStream(resources.bytes("office",png.id()))).getWidth()).isEqualTo(960);assertThat(chat.send("office",input)).isEqualTo(result);verify(model,times(1)).call(any(Prompt.class));assertThat(resources.list("office",c)).hasSize(5);
 }
 @Test void attachmentsStayWithinAccountPartnerAndConversation()throws Exception{var file=upload("a.csv","x\n1");resources.bind("office",List.of(file.id()),id(),id());assertThatThrownBy(()->resources.bind("office",List.of(file.id()),id(),id())).hasMessageContaining("当前任务");assertThatThrownBy(()->resources.get("marketing",file.id())).hasMessage("资源不存在");try(var ignored=UserContext.open(TaskWorkflowTest.B)){assertThat(resources.list("office",null)).isEmpty();assertThatThrownBy(()->resources.bytes("office",file.id())).hasMessage("资源不存在");}assertThatThrownBy(()->resources.bytes("office","../../config")).isInstanceOf(IllegalArgumentException.class);verify(model,never()).call(any(Prompt.class));}
 @Test void rejectsInvalidFilesBeforeAnyModelCall()throws Exception{assertThatThrownBy(()->upload("bad.csv","x\n\"unfinished")).hasMessageContaining("引号");assertThatThrownBy(()->upload("bad.xlsx","not zip")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->upload("bad.exe","bad")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->upload("large.csv","x".repeat(1024*1024+1))).hasMessageContaining("1MB");assertThat(resources.list("office",null)).isEmpty();verify(model,never()).call(any(Prompt.class));}
 @Test void paginationAndOldJsonResourcesPreserveHistoryBytes()throws Exception{String c=id();for(int i=0;i<17;i++)chat.send("office",PartnerChatServiceTest.input(c,i,"第"+i+"轮"));var page=chat.page("office",c,null,8);assertThat(page.turns()).hasSize(8);assertThat(page.nextBefore()).isEqualTo(9);assertThat(chat.page("office",c,9,8).turns().getFirst().text()).isEqualTo("第1轮");assertThat(chat.page("office",c,1,8).nextBefore()).isNull();
  Path path=root.resolve(TaskWorkflowTest.A.id()).resolve("conversations").resolve(c+".json");var node=json.readTree(path.toFile());for(var t:node.get("turns"))((com.fasterxml.jackson.databind.node.ObjectNode)t).remove(List.of("artifacts","attachments","events","skillId","materialContext"));json.writeValue(path.toFile(),node);byte[] before=Files.readAllBytes(path);var legacy=chat.legacyResources("office",c);assertThat(legacy).hasSize(17);assertThat(new String(chat.legacyContent("office",legacy.getFirst().id()),StandardCharsets.UTF_8)).isEqualTo("统计结论");assertThat(Files.readAllBytes(path)).isEqualTo(before);
 }
 @Test void stopPreventsLateModelAnswerFromPublishing()throws Exception{var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var returned=new CountDownLatch(1);when(model.call(any(Prompt.class))).thenAnswer(x->{entered.countDown();release.await(5,TimeUnit.SECONDS);returned.countDown();return PartnerChatServiceTest.answer("不能发布");});String c=id();try{chat.submit("office",PartnerChatServiceTest.input(c,0,"等待"));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(chat.cancel("office",c).turns().getLast().status()).isEqualTo("CANCELLED");release.countDown();assertThat(returned.await(5,TimeUnit.SECONDS)).isTrue();chat.close();assertThat(chat.get("office",c).turns().getLast().answer()).isNull();assertThat(resources.list("office",c)).isEmpty();}finally{release.countDown();}}
 @Test void providerArgumentErrorsAreNotExposed()throws Exception{when(model.call(any(Prompt.class))).thenThrow(new IllegalArgumentException("secret-provider-token"));var c=chat.send("office",PartnerChatServiceTest.input(id(),0,"hello"));assertThat(c.turns().getLast().status()).isEqualTo("FAILED");assertThat(json.writeValueAsString(c)).doesNotContain("secret-provider-token");}
}

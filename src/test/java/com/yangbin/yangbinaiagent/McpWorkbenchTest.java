package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.*;
import com.yangbin.yangbinaiagent.mcp.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.work.*;
import com.yangbin.yangbinaiagent.controller.McpController;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpWorkbenchTest {
 @TempDir Path root;final ObjectMapper json=new ObjectMapper();McpWorkbench service;UserContext.Scope scope;ObjectProvider<SyncMcpToolCallbackProvider> provider;SyncMcpToolCallbackProvider callbacks;AtomicInteger calls=new AtomicInteger();
 final String name="fixture_currentTime",schema="{\"type\":\"object\",\"required\":[\"timezone\"],\"properties\":{\"timezone\":{\"type\":\"string\"}}}";
 @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{scope=UserContext.open(TaskWorkflowTest.A);provider=mock(ObjectProvider.class);callbacks=mock(SyncMcpToolCallbackProvider.class);when(provider.getIfAvailable()).thenReturn(callbacks);when(callbacks.getToolCallbacks()).thenReturn(new ToolCallback[]{tool(name,schema,()->"2026-10-19T12:00:00+08:00")});service=new McpWorkbench(provider,json,root.toString(),1000);}
 @AfterEach void close(){service.close();scope.close();}
 String id(){return UUID.randomUUID().toString();}JsonNode args()throws Exception{return json.readTree("{\"timezone\":\"Asia/Singapore\"}");}
 ToolCallback tool(String name,String schema,java.util.function.Supplier<String> value){return new ToolCallback(){public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(name).description("fixture").inputSchema(schema).build();}public String call(String input){calls.incrementAndGet();assertThat(UserContext.current().id()).isEqualTo(TaskWorkflowTest.A.id());return value.get();}};}
 @Test void historySurvivesRestartAndDuplicateDoesNotCallAgain()throws Exception{String id=id();var result=service.call(id,name,args());assertThat(result.success()).isTrue();assertThat(result.status()).isEqualTo("SUCCEEDED");service.close();service=new McpWorkbench(provider,json,root.toString(),1000);assertThat(service.call(id,name,args())).isEqualTo(result);assertThat(calls.get()).isEqualTo(1);assertThat(service.history()).hasSize(1);assertThat(service.markdown(id)).contains(name,"服务端共享资料",id);assertThatThrownBy(()->service.call(id,name,json.readTree("{\"timezone\":\"UTC\"}"))).isInstanceOf(IllegalStateException.class);}
 @Test void invalidArgsAndUnexposedToolsNeverExecute()throws Exception{assertThatThrownBy(()->service.call(id(),name,json.readTree("{\"timezone\":3}"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.call(id(),name,json.readTree("{}"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.call(id(),name,json.readTree("{\"timezone\":\"bad-zone\"}"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.call(id(),"executeTerminalCommand",args())).isInstanceOf(IllegalArgumentException.class);assertThat(calls.get()).isZero();assertThat(service.history()).isEmpty();}
 @Test void historiesAndExportsAreOwnerScoped()throws Exception{var entry=service.call(id(),name,args());try(var ignored=UserContext.open(TaskWorkflowTest.B)){assertThat(service.history()).isEmpty();assertThatThrownBy(()->service.get(entry.id())).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.markdown(entry.id())).isInstanceOf(IllegalArgumentException.class);service.clear();}assertThat(service.history()).hasSize(1);}
 @Test void toolErrorsNullAndOversizeAreExplicit()throws Exception{when(callbacks.getToolCallbacks()).thenReturn(new ToolCallback[]{tool(name,schema,()->"{\"isError\":true,\"content\":[]}")});assertThat(service.call(id(),name,args()).status()).isEqualTo("FAILED");when(callbacks.getToolCallbacks()).thenReturn(new ToolCallback[]{tool(name,schema,()->null)});assertThat(service.call(id(),name,args()).success()).isFalse();when(callbacks.getToolCallbacks()).thenReturn(new ToolCallback[]{tool(name,schema,()->"x".repeat(20000))});var large=service.call(id(),name,args());assertThat(large.truncated()).isTrue();assertThat(large.result()).hasSizeLessThan(16100);}
 @Test void timeoutDoesNotAutomaticallyRetry()throws Exception{service.close();service=new McpWorkbench(provider,json,root.toString(),30);when(callbacks.getToolCallbacks()).thenReturn(new ToolCallback[]{tool(name,schema,()->{try{Thread.sleep(1000);}catch(InterruptedException ignored){}return "late";})});String id=id();var result=service.call(id,name,args());assertThat(result.status()).isEqualTo("TIMEOUT");assertThat(service.call(id,name,args())).isEqualTo(result);assertThat(calls.get()).isEqualTo(1);}
 @Test void restartMarksUnfinishedCallUnknownInsteadOfRetrying()throws Exception{String id=id();Path dir=root.resolve(TaskWorkflowTest.A.id()).resolve("mcp");Files.createDirectories(dir);json.writeValue(dir.resolve(id+".json").toFile(),new McpWorkbench.Entry(id,name,args(),"RUNNING",false,"",0,"2026-10-19T00:00:00Z",false));service.close();service=new McpWorkbench(provider,json,root.toString(),1000);assertThat(service.get(id).status()).isEqualTo("INTERRUPTED");assertThat(service.call(id,name,args()).status()).isEqualTo("INTERRUPTED");assertThat(calls.get()).isZero();}
 @Test void boundedHistoryKeepsMostRecentFifty()throws Exception{for(int i=0;i<53;i++)service.call(id(),name,args());assertThat(service.history()).hasSize(50);service.clear();assertThat(service.history()).isEmpty();}
 @Test void discoveryFailureIsReportedWithoutClaimingHealthy()throws Exception{assertThat(service.catalog().get("status")).isEqualTo("DISCOVERED");when(callbacks.getToolCallbacks()).thenThrow(new IllegalStateException("internal detail"));assertThat(service.catalog().get("status")).isEqualTo("ERROR");assertThat(service.catalog().toString()).doesNotContain("internal detail");}
 @Test void concurrentDuplicateDoesNotExecuteAndRunningHistoryCannotBeCleared()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(callbacks.getToolCallbacks()).thenReturn(new ToolCallback[]{tool(name,schema,()->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return "done";})});String id=id();JsonNode a=args();
  var executor=Executors.newSingleThreadExecutor();try{var first=executor.submit(()->{try(var ignored=UserContext.open(TaskWorkflowTest.A)){return service.call(id,name,a);}});assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(service.call(id,name,a).status()).isEqualTo("RUNNING");assertThatThrownBy(service::clear).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->service.call(id(),name,a)).isInstanceOf(IllegalStateException.class);release.countDown();assertThat(first.get().success()).isTrue();assertThat(calls.get()).isEqualTo(1);}finally{release.countDown();executor.shutdownNow();}
 }
 @Test void partnerHandoffIsIdempotentBoundedAndSurvivesHistoryClear()throws Exception{
  var resources=new PartnerResources(json,new WorkCatalog(json),root.toString());var work=mock(WorkService.class);when(work.policy("office")).thenReturn(new WorkService.PartnerPolicy("ALLOW",true));var controller=new McpController(service,resources,work);
  var entry=service.call(id(),name,args());var input=new McpController.Attach("office");var imported=(PartnerResources.Resource)controller.attach(entry.id(),input);assertThat(controller.attach(entry.id(),input)).isEqualTo(imported);assertThat(resources.list("office",null)).hasSize(1);assertThat(resources.analyze("office",List.of(imported)).context()).contains("MCP",entry.id());
  try(var ignored=UserContext.open(TaskWorkflowTest.B)){assertThatThrownBy(()->controller.attach(entry.id(),input)).isInstanceOf(IllegalArgumentException.class);}
  when(work.policy("office")).thenReturn(new WorkService.PartnerPolicy("RESTRICTED",false));assertThatThrownBy(()->controller.attach(entry.id(),input)).isInstanceOf(IllegalStateException.class);
  service.clear();assertThat(resources.bytes("office",imported.id())).isNotEmpty();
 }
 @Test void schemaValidatorAcceptsTypedArgumentsAndRejectsMissingUnknownAndUnsupported()throws Exception{
  var s=json.readTree("{\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1},\"ok\":{\"type\":\"boolean\"},\"names\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]}}}}");
  McpArguments.validate(s,json.readTree("{\"n\":2,\"ok\":true,\"names\":[\"a\"]}"));
  for(String bad:List.of("{}","{\"n\":0}","{\"n\":1.5}","{\"n\":1,\"oops\":true}","{\"n\":1,\"names\":[\"c\"]}"))assertThatThrownBy(()->McpArguments.validate(s,json.readTree(bad))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->McpArguments.validate(json.readTree("{\"$ref\":\"remote\"}"),args())).isInstanceOf(IllegalArgumentException.class);
 }
}

package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.work.*;
import com.yangbin.yangbinaiagent.controller.WorkPresentationController;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.sl.usermodel.Placeholder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkPresentationTest {
    static final String FONT=System.getProperty("test.presentation.font","Microsoft YaHei");
    @TempDir Path root;
    ObjectMapper json=new ObjectMapper();
    ChatModel model;
    WorkCatalog catalog;
    WorkService work;
    WorkPresentationService exports;
    UserContext.Scope identity;
    @BeforeEach void setup() throws Exception {
        identity=UserContext.open(TaskWorkflowTest.A);
        model=mock(ChatModel.class); catalog=new WorkCatalog(json);
        work=new WorkService(json,model,catalog,ExecutionProvenance.unknown(),root.toString());
        exports=new WorkPresentationService(work,json,root.toString(),FONT);
        clearInvocations(model);
    }
    @AfterEach void close(){identity.close();}
    PresentationDraft draft() {
        return new PresentationDraft("新品内容活动：两周执行计划",List.of(
            new PresentationDraft.Slide("新品内容活动",List.of("环保笔记本 · 两周内容计划","预算与渠道待确认"),"这是一份合成案例，用于演示文件交付；所有目标需要结合实际资料确认。"),
            new PresentationDraft.Slide("目标与待确认条件",List.of("先验证受众与卖点，再决定扩大投入","预算、渠道和历史表现尚待补充","当前不承诺销售额或转化增长"),"请先确认产品真实参数、预算以及可用素材。本页区分已知条件和需要补充的材料。"),
            new PresentationDraft.Slide("第一周：整理材料与制作初稿",List.of("确认产品参数与可用素材","围绕三个真实卖点各制作一组文案","评审文案依据，记录需要修改的表达"),"三个卖点需来自产品资料。此处描述计划动作，并非已经执行的结果。"),
            new PresentationDraft.Slide("第二周：验证与调整",List.of("在确认的渠道开展小范围测试","按统一口径观察反馈","保留表现差的素材记录，说明调整原因"),"需要先确认渠道、流量规模和比较窗口。不用未经验证的指标证明效果。"),
            new PresentationDraft.Slide("复盘记录",List.of("记录素材版本、展示条件与反馈","区分真实观察、解释和待验证假设","用结果决定下一轮要解决的问题"),"建议保存素材原文和统计口径，避免把计划目标写成已经实现的收益。"),
            new PresentationDraft.Slide("下一步安排",List.of("补充产品资料、预算和渠道","确认负责人及素材评审时间","确定第一轮的比较指标与停止条件"),"确认完缺失条件后再开展执行。本页所有事项均为计划，尚未实际实施。")));
    }
    WorkService.Request request() {return new WorkService.Request(UUID.randomUUID().toString(),"presentation-outline","presentation","请根据给定资料制作6页新品活动方案，包含封面。");}
    WorkService.Run generate() throws Exception {
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt=call.getArgument(0);
            assertThat(prompt.getContents()).contains("只返回JSON","最多20页","讲稿");
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json.writeValueAsString(draft())))));
        });
        return work.execute(request());
    }
    @Test void generatesEditableSixPageDeckWithNotesAndKeepsOriginalTaskBytes() throws Exception {
        var run=generate();
        assertThat(run.status()).isEqualTo("SUCCEEDED"); assertThat(run.skill().version()).isEqualTo(2);
        assertThat(run.presentation()).isEqualTo(draft()); assertThat(run.result()).contains("## 6.","讲稿");
        Path record=root.resolve(TaskWorkflowTest.A.id()).resolve(run.id()+".json");
        byte[] before=Files.readAllBytes(record);
        var artifact=exports.export(run.id()); byte[] bytes=exports.download(run.id());
        assertThat(artifact.slideCount()).isEqualTo(6); assertThat(artifact.sha256()).hasSize(64);
        try(var deck=new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(deck.getSlides()).hasSize(6);
            for(int i=0;i<6;i++) {
                var slide=deck.getSlides().get(i);
                assertThat(slide.getShapes()).allMatch(s -> s instanceof XSLFTextShape);
                String text=slide.getShapes().stream().map(s -> ((XSLFTextShape)s).getText()).reduce("",(a,b) -> a+"\n"+b);
                assertThat(text).contains(draft().slides().get(i).title());
                for(String point:draft().slides().get(i).points())assertThat(text).contains(point);
                var notes=deck.getNotesSlide(slide);
                assertThat(notes.getShapes().stream().filter(s -> s instanceof XSLFTextShape)
                    .map(s -> (XSLFTextShape)s).filter(s -> s.getTextType()==Placeholder.BODY).map(XSLFTextShape::getText).toList())
                    .containsExactly(draft().slides().get(i).notes());
            }
        }
        assertThat(Files.readAllBytes(record)).isEqualTo(before);
        verify(model,times(1)).call(any(Prompt.class));
        Path evidence=Path.of("target/presentation-example"); Files.createDirectories(evidence);
        Files.write(evidence.resolve("marketing-example.pptx"),bytes);
        json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("run.json").toFile(),run);
        json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("artifact.json").toFile(),artifact);
    }
    @Test void cachedFileIsReusedAndCorruptFileCanBeRebuiltWithoutModel() throws Exception {
        var run=generate(); var first=exports.export(run.id()); byte[] bytes=exports.download(run.id());
        assertThat(exports.export(run.id())).isEqualTo(first);
        assertThat(exports.download(run.id())).isEqualTo(bytes);
        Files.writeString(root.resolve(TaskWorkflowTest.A.id()).resolve("artifacts").resolve(run.id()).resolve("presentation.pptx"),"damaged");
        assertThatThrownBy(() -> exports.download(run.id())).isInstanceOf(IllegalStateException.class);
        var repaired=exports.export(run.id()); assertThat(exports.download(run.id()).length).isEqualTo(repaired.bytes());
        Files.writeString(root.resolve(TaskWorkflowTest.A.id()).resolve("artifacts").resolve(run.id()).resolve("artifact.json"),"{broken");
        var rebuilt=exports.export(run.id()); assertThat(exports.download(run.id()).length).isEqualTo(rebuilt.bytes());
        verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void ownershipIsCheckedBeforeExportAndDownloadAndHttpHeadersUseAttachment() throws Exception {
        var run=generate(); var controller=new WorkPresentationController(exports);
        assertThatThrownBy(() -> controller.download(run.id())).isInstanceOf(IllegalArgumentException.class);
        controller.export(run.id());
        var response=controller.download(run.id());
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment","yangbin-"+run.id()+".pptx");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThatThrownBy(() -> controller.export(run.id())).isInstanceOf(IllegalArgumentException.class).hasMessage("任务不存在");
            assertThatThrownBy(() -> controller.download(run.id())).isInstanceOf(IllegalArgumentException.class).hasMessage("任务不存在");
            assertThat(Files.exists(root.resolve(TaskWorkflowTest.B.id()).resolve("artifacts"))).isFalse();
        }
        assertThatThrownBy(() -> controller.export("../outside")).isInstanceOf(IllegalArgumentException.class);
        verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void strictContractRejectsOversizedOrMissingDataAndTrailingObjects() throws Exception {
        String valid=json.writeValueAsString(draft());
        assertThat(PresentationDraft.parse(json,valid)).isEqualTo(draft());
        assertThatThrownBy(() -> PresentationDraft.parse(json,valid+"{}")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> PresentationDraft.parse(json,"{\"title\":\"x\",\"slides\":[],\"unexpected\":1}")).isInstanceOf(Exception.class);
        for(var bad:List.of(new PresentationDraft("标题",Collections.nCopies(21,draft().slides().get(0))),
            new PresentationDraft("标题",List.of(new PresentationDraft.Slide("标题",List.of("字".repeat(81)),""))),
            new PresentationDraft("标题",List.of(new PresentationDraft.Slide("标题",List.of("正常"),null))))) {
            assertThatThrownBy(() -> PresentationDraft.validate(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void invalidModelStructureFailsWithoutPublishingOrRetrying() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("# 非结构化文稿")))));
        var req=request();var failed=work.execute(req);
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.presentation()).isNull();
        assertThatThrownBy(() -> exports.export(failed.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(work.execute(req)).isEqualTo(failed);verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void legacyTextRecordWithoutPresentationFieldStillLoadsAndCannotBeFalselyExported() throws Exception {
        var skill=catalog.skill("presentation-outline");
        var old=new WorkCatalog.Skill(skill.id(),skill.name(),skill.category(),skill.icon(),skill.color(),skill.description(),
            "Markdown大纲", "生成旧式大纲",skill.example(),skill.tags(),1,skill.kind());
        String id=UUID.randomUUID().toString();
        var legacy=new WorkService.Run(id,"旧演示","2026-10-16T00:00:00Z","2026-10-16T00:00:00Z","SUCCEEDED",
            old,null,"旧材料","# 原文稿",null,null);
        var tree=json.valueToTree(legacy);((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("presentation");
        Path folder=root.resolve(TaskWorkflowTest.A.id());Files.createDirectories(folder);json.writeValue(folder.resolve(id+".json").toFile(),tree);
        assertThat(work.get(id).result()).isEqualTo("# 原文稿");assertThat(work.get(id).presentation()).isNull();
        assertThatThrownBy(() -> exports.export(id)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(model);
    }
    @Test void denseChineseTextRemainsEditableAndImpossibleLayoutIsRejected() throws Exception {
        var dense=new PresentationDraft("密集文本边界",List.of(new PresentationDraft.Slide("标题".repeat(20),
            Collections.nCopies(4,"正文".repeat(40)),"讲稿".repeat(1000))));
        byte[] bytes=PresentationRenderer.render(dense,FONT);
        try(var deck=new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(deck.getSlides()).hasSize(1);
            for(var shape:deck.getSlides().get(0).getShapes())if(shape instanceof XSLFTextShape text) {
                assertThat(text.getTextHeight()).isLessThanOrEqualTo(text.getAnchor().getHeight());
            }
        }
        var impossible=new PresentationDraft("标题",List.of(new PresentationDraft.Slide("标题",Collections.nCopies(4,"字\n".repeat(40)),"")));
        assertThatThrownBy(() -> PresentationRenderer.render(impossible,FONT)).isInstanceOf(IllegalArgumentException.class);
    }
}

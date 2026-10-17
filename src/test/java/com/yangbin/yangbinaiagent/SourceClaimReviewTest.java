package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.*;
import com.yangbin.yangbinaiagent.planner.*;
import com.yangbin.yangbinaiagent.controller.PlannerController;
import com.yangbin.yangbinaiagent.rag.KnowledgeService;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceClaimReviewTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path DATA=Path.of("evaluation/datasets/travel-eval-v1/cases.json");
    @TempDir Path root;
    JsonNode input() throws Exception {return JSON.readTree(DATA.toFile()).path("cases").get(0).path("input");}
    PlannerService.Requirements req() throws Exception {return JSON.treeToValue(input().path("requirements"),PlannerService.Requirements.class);}
    PlanningEvidence.Snapshot evidence() throws Exception {return JSON.treeToValue(input().path("evidence"),PlanningEvidence.Snapshot.class);}
    PlannerService.Itinerary itinerary(String text) {return new PlannerService.Itinerary("测试",text,List.of(),List.of(),List.of());}
    SourceClaimReview.Report review(String text) throws Exception {return SourceClaimReview.assess(req(),itinerary(text),evidence());}
    List<SourceClaimReview.Claim> alerts(SourceClaimReview.Report report) {
        return report.claims().stream().filter(c->Set.of("UNSUPPORTED","CONTRADICTED").contains(c.verdict())).toList();
    }
    @Test void dailyForecastChecksExactEvidenceWithoutClaimingWholeTextCorrect() throws Exception {
        var match=review("日预报为多云。");
        assertThat(match.claims()).anyMatch(c->c.category().equals("DAILY_FORECAST")&&c.verdict().equals("SUPPORTED"));
        assertThat(match.status()).isEqualTo("NO_UNSUPPORTED_CLAIMS_DETECTED");
        assertThat(review("日预报为晴。").contradictedClaims()).isEqualTo(1);
    }
    @Test void probabilityEqualityDoesNotImplyGuaranteedWeather() throws Exception {
        var equal=review("降水概率为30%。");
        assertThat(alerts(equal)).isEmpty();
        assertThat(equal.claims()).anyMatch(c->c.category().equals("RAIN_PROBABILITY")&&c.verdict().equals("SUPPORTED"));
        assertThat(review("降水概率为90%。").contradictedClaims()).isEqualTo(1);
        assertThat(review("降水概率为30%，保证全天无雨。").unsupportedClaims()).isEqualTo(1);
    }
    @Test void wrongDayOrMissingWeatherCannotBeAssumedContradictory() throws Exception {
        var e=evidence();var w=e.weather();
        var stale=new PlanningEvidence.Snapshot(e.fetchedAt(),e.location(),
            new PlanningEvidence.Weather("2020-01-01",w.description(),w.minC(),w.maxC(),w.rainProbability(),w.url()),
            e.places(),e.weatherStatus(),e.placesStatus(),e.notices());
        var result=SourceClaimReview.assess(req(),itinerary("所选日期日预报为多云。"),stale);
        assertThat(result.unsupportedClaims()).isEqualTo(1);assertThat(result.contradictedClaims()).isZero();
        assertThat(SourceClaimReview.assess(req(),itinerary("日预报为多云。"),null).unsupportedClaims()).isEqualTo(1);
    }
    @Test void negationConditionalAndRequestDoNotTriggerFactAlerts() throws Exception {
        for(String text:List.of("尚未核实票价，也没有确认当天营业。","如果下雨，可改室内。","先确认展览、预约和实际票价。",
            "不能保证全天无雨。","想看古代青铜器展。","如果有专题展，可以参观。","请确认步行20分钟是否可行。")) {
            assertThat(alerts(review(text))).as(text).isEmpty();
        }
    }
    @Test void estimatesAbstainInsteadOfBecomingVerifiedFacts() throws Exception {
        var result=review("预计步行18分钟。门票预算每人90元。可能有油画展。");
        assertThat(alerts(result)).isEmpty();
        assertThat(result.claims()).noneMatch(c->c.verdict().equals("SUPPORTED"));
        assertThat(result.unassessedClauses()).isGreaterThanOrEqualTo(3);
    }
    @Test void clauseLocalDisclaimerCannotHideSeparateCertainClaim() throws Exception {
        for(String text:List.of("尚未核实票价，但是门票确定为90元。","如果下雨就去室内，已确认当天营业。","可能有雨；两站步行刚好18分钟。")) {
            assertThat(alerts(review(text))).as(text).hasSize(1);
        }
    }
    @Test void timeWindowExhibitionPriceBookingAndRouteHaveSeparateReasons() throws Exception {
        var result=review("16点10分云层散去。观看馆藏油画特展。票价确定90元。当天营业至21点。无需预约。步行刚好18分钟。");
        assertThat(alerts(result)).extracting(SourceClaimReview.Claim::category)
            .contains("WEATHER_PRECISION","SPECIFIC_EXHIBITION","CURRENT_PRICE","OPENING_OR_BOOKING","ROUTE_TIME_OR_DISTANCE");
        assertThat(alerts(result)).allMatch(c->c.verdict().equals("UNSUPPORTED")&&!c.evidencePointers().isEmpty());
    }
    @Test void evidencePointersAndUtf16OffsetsResolveForEmojiAndWhitespace() throws Exception {
        String text="😀 票价确定90元；\n  步行刚好18分钟。";
        var r=review(text);var subject=JSON.createObjectNode();
        subject.set("requirements",JSON.valueToTree(req()));subject.set("itinerary",JSON.valueToTree(itinerary(text)));
        subject.set("evidence",JSON.valueToTree(evidence()));
        for(var claim:r.claims()) {
            String original=subject.at(claim.field()).asText();
            assertThat(original.substring(claim.start(),claim.end())).isEqualTo(claim.text());
            for(var pointer:claim.evidencePointers())assertThat(subject.at(pointer).isMissingNode()).isFalse();
        }
    }
    @Test void inputBindingChangesWithEvidenceAndUnsupportedFormatsAbstain() throws Exception {
        var r=review("日预报为多云。");
        assertThat(r.inputHash()).isEqualTo(review("日预报为多云。").inputHash());
        assertThat(r.inputHash()).isNotEqualTo(SourceClaimReview.assess(req(),itinerary("日预报为多云。"),null).inputHash());
        assertThat(SourceClaimReview.supports("en","ragtruth-generic-v1")).isFalse();
        assertThat(review("The venue has a rare exhibition.").claims()).allMatch(c->c.verdict().equals("NOT_ASSESSABLE"));
    }
    @Test @SuppressWarnings("unchecked") void endpointIsOwnerScopedReadOnlyAndReturnsExactVersion() throws Exception {
        var model=mock(ChatModel.class);var upstream=mock(PlanningEvidence.class);
        var planner=new PlannerService(model,upstream,JSON,mock(ObjectProvider.class),root.resolve("plans").toString());
        var controller=new PlannerController(planner,upstream);
        clearInvocations(model,upstream); // ChatClient construction reads default options, but review must make no calls.
        String id=UUID.randomUUID().toString();byte[] before;
        try(var identity=UserContext.open(TaskWorkflowTest.A)) {
            var i=JSON.treeToValue(input().path("itinerary"),PlannerService.Itinerary.class);
            planner.saveTaskPlan(id,req(),i,List.of(),evidence());
            before=Files.readAllBytes(root.resolve("plans/"+id+"-1.json"));
            var result=(SourceClaimReview.PlanReport)controller.sourceReview(id,1);
            assertThat(result.planId()).isEqualTo(id);assertThat(result.planVersion()).isEqualTo(1);
            assertThat(result.review().version()).isEqualTo(SourceClaimReview.VERSION);
            assertThat(Files.readAllBytes(root.resolve("plans/"+id+"-1.json"))).isEqualTo(before);
            assertThatThrownBy(()->controller.sourceReview(id,2)).isInstanceOf(IllegalArgumentException.class);
        }
        try(var identity=UserContext.open(TaskWorkflowTest.B)) {
            assertThatThrownBy(()->controller.sourceReview(id,1)).isInstanceOf(IllegalArgumentException.class).hasMessage("方案不存在");
        }
        verifyNoInteractions(model,upstream);
    }
    @Test void externalHumanSubsetReportsApplicabilityBeforeAttemptingPredictions() throws Exception {
        Path dataset=Path.of("evaluation/datasets/ragtruth-test-source24-v1");
        var manifest=JSON.readTree(dataset.resolve("manifest.json").toFile());
        for(var it=manifest.path("files").fields();it.hasNext();) {
            var file=it.next();assertThat(FrozenDatasetEvaluationTest.hash(dataset.resolve(file.getKey()))).isEqualTo(file.getValue().asText());
        }
        var report=JSON.createObjectNode();
        report.put("schemaVersion","source-review-external-applicability-v1").put("datasetHash",manifest.path("datasetHash").asText())
            .put("detectorVersion",SourceClaimReview.VERSION).put("upstreamModelRequests",0);
        var rows=report.putArray("cases");
        for(var c:JSON.readTree(dataset.resolve("cases.json").toFile()).path("cases")) {
            boolean supported=SourceClaimReview.supports("en","ragtruth-generic-v1");
            var row=rows.addObject();row.set("caseId",c.get("caseId"));row.set("caseHash",c.get("caseHash"));
            row.put("applicable",supported).put("prediction","NOT_ASSESSABLE").put("reason","Current checker requires Chinese travel snapshot schema; external general evidence is not adapted");
            assertThat(supported).isFalse();
        }
        report.set("codeSnapshot",FrozenDatasetEvaluationTest.codeSnapshot());
        String id=UUID.randomUUID().toString();
        Path output=Path.of("target/source-review").resolve(id).resolve("external-applicability.json");
        FrozenDatasetEvaluationTest.save(output,report);
        System.out.println("External applicability report: "+output.toAbsolutePath());
        assertThat(rows.size()).isEqualTo(24);
    }
}

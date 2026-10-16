package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yangbin.yangbinaiagent.planner.PlanQualityService;
import com.yangbin.yangbinaiagent.planner.PlannerService;
import com.yangbin.yangbinaiagent.planner.PlanningEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

/** Fixed rule regression cases, deliberately distinct from model or factual-accuracy evaluation. */
class PlanQualityServiceTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String RESOURCE="/quality/plan-quality-regression-v2.json";
    private static byte[] fixtureBytes() throws Exception {
        try(var stream=PlanQualityServiceTest.class.getResourceAsStream(RESOURCE)) {
            if(stream==null)throw new IllegalStateException("Missing fixture "+RESOURCE);
            return stream.readAllBytes();
        }
    }
    private static JsonNode fixture() throws Exception {return JSON.readTree(fixtureBytes());}
    /** JSON merge semantics are recorded in the fixture; arrays and null replace the whole value. */
    private static JsonNode merge(JsonNode base,JsonNode overrides) {
        if(overrides==null)return base.deepCopy();
        if(!base.isObject()||!overrides.isObject())return overrides.deepCopy();
        ObjectNode result=base.deepCopy();
        overrides.fields().forEachRemaining(entry->{
            JsonNode current=result.get(entry.getKey());
            result.set(entry.getKey(),current==null?entry.getValue().deepCopy():merge(current,entry.getValue()));
        });
        return result;
    }
    private static PlanQualityService.Report assess(JsonNode input) throws Exception {
        return PlanQualityService.assess(JSON.treeToValue(input.get("requirements"),PlannerService.Requirements.class),
                JSON.treeToValue(input.get("itinerary"),PlannerService.Itinerary.class),
                JSON.treeToValue(input.get("evidence"),PlanningEvidence.Snapshot.class));
    }
    private static SortedSet<String> warnings(PlanQualityService.Report report) {
        var result=new TreeSet<String>();
        report.findings().stream().filter(f->"warning".equalsIgnoreCase(f.severity())).forEach(f->result.add(f.code()));
        return result;
    }
    private static SortedSet<String> expectedWarnings(JsonNode expected) {
        var result=new TreeSet<String>();expected.path("warningCodes").forEach(code->result.add(code.asText()));return result;
    }

    @Test void fixedCasesGenerateAuditableRuleEvaluationReport() throws Exception {
        byte[] bytes=fixtureBytes();JsonNode dataset=JSON.readTree(bytes);
        var observations=new ArrayList<Map<String,Object>>();var assertions=new ArrayList<Executable>();
        int matched=0,truePositive=0,falsePositive=0,trueNegative=0,falseNegative=0,synthetic=0,historical=0;
        for(JsonNode item:dataset.path("cases")) {
            String id=item.path("id").asText();JsonNode expected=item.path("expected");
            if("synthetic".equals(item.path("sourceKind").asText()))synthetic++;else historical++;
            JsonNode input=merge(dataset.path("defaults"),item.path("overrides"));
            var observation=new LinkedHashMap<String,Object>();observation.put("id",id);observation.put("description",item.path("description").asText());
            observation.put("sourceKind",item.path("sourceKind").asText());observation.put("expected",expected);
            try {
                var report=assess(input);var actualCodes=warnings(report);var expectedCodes=expectedWarnings(expected);
                var actual=new LinkedHashMap<String,Object>();actual.put("status",report.status());actual.put("warningCodes",actualCodes);
                actual.put("matchedStops",report.matchedStops());actual.put("totalStops",report.totalStops());
                actual.put("ruleVersion",report.ruleVersion());actual.put("checks",report.checks());actual.put("findings",report.findings());actual.put("limitations",report.limitations());
                observation.put("observed",actual);
                boolean matches=expected.path("status").asText().equals(report.status())&&expectedCodes.equals(actualCodes)
                        &&expected.path("matchedStops").asInt()==report.matchedStops()&&expected.path("totalStops").asInt()==report.totalStops()
                        &&dataset.path("ruleVersion").asText().equals(report.ruleVersion());
                observation.put("matchesExpected",matches);if(matches)matched++;
                if(expectedCodes.isEmpty()){if(actualCodes.isEmpty())trueNegative++;else falsePositive++;}
                else {if(actualCodes.isEmpty())falseNegative++;else truePositive++;}
                assertions.add(()->{
                    assertThat(report.ruleVersion()).as(id+" rule version").isEqualTo(dataset.path("ruleVersion").asText());
                    assertThat(report.status()).as(id+" status").isEqualTo(expected.path("status").asText());
                    assertThat(actualCodes).as(id+" warning codes").isEqualTo(expectedCodes);
                    assertThat(report.matchedStops()).as(id+" matched stops").isEqualTo(expected.path("matchedStops").asInt());
                    assertThat(report.totalStops()).as(id+" total stops").isEqualTo(expected.path("totalStops").asInt());
                    assertThat(report.limitations()).as(id+" explicit scope limits").isNotEmpty();
                    assertThatCode(()->Instant.parse(report.checkedAt())).as(id+" check timestamp").doesNotThrowAnyException();
                });
            } catch(Exception failure) {
                observation.put("matchesExpected",false);observation.put("error",failure.getClass().getSimpleName()+": "+failure.getMessage());
                assertions.add(()->{throw new AssertionError(id+" raised exception",failure);});
            }
            observations.add(observation);
        }
        var output=new LinkedHashMap<String,Object>();
        output.put("datasetId",dataset.path("datasetId").asText());output.put("ruleVersion",dataset.path("ruleVersion").asText());
        output.put("fixtureSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        output.put("fixtureDeclaration",dataset.path("fixtureDeclaration").asText());output.put("generatedAt",Instant.now().toString());
        output.put("denominator",observations.size());output.put("syntheticCases",synthetic);output.put("historicalExcerptCases",historical);
        output.put("exactCasesMatchingExpected",matched);
        output.put("warningPresenceCounts",Map.of("expectedWarningAndObservedWarning",truePositive,"expectedNoWarningButObservedWarning",falsePositive,
                "expectedNoWarningAndObservedNoWarning",trueNegative,"expectedWarningButObservedNoWarning",falseNegative));
        output.put("interpretation","固定样例的规则回归符合性；不是模型准确率、事实正确率或真实用户总体效果。历史原文只重放规则，不重新调用模型。数量统计不覆盖所有自然语言表达。规则与样例共同开发，不是盲测。");
        output.put("cases",observations);
        Path target=Path.of("target","quality-evaluation.json");Files.createDirectories(target.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(),output);
        assertThat(observations).hasSize(26);assertAll("fixed grounding rule cases",assertions);
    }

    @Test void unsafeOrCredentialBearingLinksAreNeverReturnedAsSources() throws Exception {
        var baseline=fixture().path("defaults");
        var unsafe=List.of("javascript:alert(1)","data:text/html,unsafe","file:///etc/passwd","https://name:password@example.com/source",
                "https://example.com/?api_key=FAKE_PRIVATE_VALUE","https://example.com/?access_token=FAKE_PRIVATE_VALUE",
                "https://example.com/?%6bey=FAKE_PRIVATE_VALUE","https://example.com/?Signature=FAKE_PRIVATE_VALUE");
        for(String url:unsafe) {
            ObjectNode input=baseline.deepCopy();
            ((ObjectNode)input.path("evidence").path("weather")).put("url",url);
            ((ObjectNode)input.path("evidence").path("location")).put("url",url);
            ((ObjectNode)input.path("evidence").path("places").get(0)).put("url",url);
            var report=assess(input);
            assertThat(report.checks()).as(url).allSatisfy(check->assertThat(check.sourceUrl()).isNull());
            assertThat(JSON.writeValueAsString(report)).doesNotContain("FAKE_PRIVATE_VALUE","password@example.com","javascript:","data:text/html","file://");
        }
    }

    @Test void validSourcesRemainUsableWithoutClaimingFullFactVerification() throws Exception {
        var report=assess(fixture().path("defaults"));
        assertThat(report.status()).isEqualTo("CHECKED_WITH_LIMITATIONS");
        assertThat(report.checks().stream().map(PlanQualityService.Check::sourceUrl).filter(Objects::nonNull))
                .anyMatch(url->url.equals("https://www.openstreetmap.org/node/42"));
        report.checks().stream().map(PlanQualityService.Check::sourceUrl).filter(Objects::nonNull).forEach(url->{
            URI parsed=URI.create(url);assertThat(parsed.getScheme()).isIn("http","https");assertThat(parsed.getUserInfo()).isNull();
        });
        assertThat(report.limitations()).isNotEmpty();
        assertThat(report.limitations()).anyMatch(text->text.contains("价格")&&text.contains("核验"));
    }

    @Test void oldSnapshotWithNullFieldsIsHandledWithoutInventingSupport() throws Exception {
        ObjectNode input=fixture().path("defaults").deepCopy();
        ((ObjectNode)input.path("evidence")).putNull("places").putNull("notices").putNull("weather").putNull("weatherStatus");
        var report=assess(input);
        assertThat(report.matchedStops()).isZero();
        assertThat(report.status()).isIn("CHECKED_WITH_LIMITATIONS","REVIEW_REQUIRED");
        assertThat(report.checks()).noneMatch(check->"SUPPORTED".equals(check.status())&&check.sourceUrl()!=null);
    }
}

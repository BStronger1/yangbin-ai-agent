package com.yangbin.yangbinaiagent.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.coding.CodingService;
import com.yangbin.yangbinaiagent.controller.WorkEvidenceController;
import com.yangbin.yangbinaiagent.models.ModelProfileService.Snapshot;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.work.WorkEvidenceService.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkEvidenceTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    final Clock now = Clock.fixed(Instant.parse("2026-11-10T05:00:00Z"), ZoneOffset.UTC);
    final Snapshot model = new Snapshot("office-model","Office","custom","https://a.example/v1","m","openai-completions");
    final String project = uuid();
    UserContext.Scope owner;
    WorkEvidenceService service;
    PartnerResources resources;
    Report report;
    ReportInput reportRequest;
    String csvId;

    @BeforeEach void setup() throws Exception {
        owner = UserContext.open(new UserContext.User("a".repeat(32), "user"));
        resources = new PartnerResources(json, new WorkCatalog(json), root.toString());
        service = new WorkEvidenceService(resources, json, root, now);
        csvId = upload("order_id,amount,private\r\nOCT-101,1100,secret-A\r\nOCT-102,1200,secret-B\r\nOCT-103,1020,secret-C\r\nOCT-104,680,private-even-selected");
        reportRequest = new ReportInput(uuid(), "office", csvId, "order_id", "amount", decimal("3320"));
        report = service.createReport(reportRequest);
    }
    @AfterEach void close() { owner.close(); }
    static String uuid() { return UUID.randomUUID().toString(); }
    static BigDecimal decimal(String value) { return new BigDecimal(value); }
    String upload(String contents) throws Exception {
        return resources.upload("office", new MockMultipartFile("file", "orders.csv", "text/csv", contents.getBytes(StandardCharsets.UTF_8))).id();
    }
    TransferInput grant(String key, List<Integer> rows, String purpose, boolean totals, boolean tests) throws Exception {
        return new TransferInput(key, report.id(), report.sourceVersion(), rows, project, model.profileId(),
                service.destination(model).version(), purpose, totals, true, tests);
    }

    @Test void reportRecomputesAmountsAndLogicalRowsAndRejectsPlausibleButWrongCitations() throws Exception {
        assertThat(report.actualTotal()).isEqualByComparingTo("4000");
        assertThat(report.difference()).isEqualByComparingTo("680");
        assertThat(report.matchingRows()).containsExactly(5);
        assertThat(service.verify(report.id(), new Claim(decimal("4000"), decimal("680"), 5, "OCT-104", decimal("680"))).verified()).isTrue();
        var wrong = service.verify(report.id(),new Claim(decimal("3999"),decimal("681"),4,"OCT-104",decimal("680")));
        assertThat(wrong.verified()).isFalse();
        assertThat(wrong.failedChecks()).containsExactly("TOTAL","DIFFERENCE","SOURCE_ROW");
        assertThat(service.reports("office",csvId)).containsExactly(report);
        assertThat(service.recentReports()).extracting(ReportSummary::id).containsExactly(report.id());
        assertThat(service.createReport(reportRequest)).isEqualTo(report);
        assertThatThrownBy(() -> service.createReport(new ReportInput(report.id(),"office",csvId,"order_id","amount",decimal("5")))).isInstanceOf(IllegalStateException.class);
    }
    @Test void transferReadsConfirmedRowsOnlyEvenAfterSourceUpdateAndServiceRestart() throws Exception {
        var request = grant(uuid(), List.of(5), "ANALYZE", false, false);
        var transfer = service.createTransfer(request, model);
        var path = root.resolve(UserContext.current().id()).resolve("resources/office").resolve(csvId+".bin");
        Files.writeString(path, "order_id,amount\nOTHER-VERSION,680");
        service = new WorkEvidenceService(resources,json,root,now);
        assertThat(service.report(report.id())).isEqualTo(report);
        var disclosed = service.redeem(transfer.id(), project, model, "READ");
        var expected = json.readTree(disclosed);
        assertThat(expected.size()).isEqualTo(2);
        assertThat(expected.path("rows").size()).isEqualTo(1);
        assertThat(expected.at("/rows/0/orderId").asText()).isEqualTo("OCT-104");
        assertThat(expected.at("/rows/0/row").asInt()).isEqualTo(5);
        assertThat(disclosed).doesNotContain("OCT-101","private-even-selected","secret","OTHER-VERSION","Total","difference");
        assertThat(service.createTransfer(request,model)).isEqualTo(transfer);
        assertThatThrownBy(() -> service.createTransfer(grant(transfer.id(),List.of(2),"ANALYZE",false,false), model)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.redeem(transfer.id(),project,model,"PROPOSE_PATCH")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.authorize(transfer.id(),project,model,"VERIFY")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.authorize(transfer.id(),project,model,"APPLY")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.authorize(transfer.id(),uuid(),model,"READ")).isInstanceOf(IllegalStateException.class);
        try(var ignored = UserContext.open(new UserContext.User("b".repeat(32),"user"))) {
            assertThat(service.recentReports()).isEmpty();
            assertThatThrownBy(() -> service.inspect(transfer.id())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.report(report.id())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void destinationAndSourceAreRevalidatedAndGrantsCanExpireOrBeRevoked() throws Exception {
        var request = grant(uuid(),List.of(5),"PROPOSE_PATCH",true,true);
        var transfer = service.createTransfer(request,model);
        var newEndpoint = new Snapshot(model.profileId(),model.name(),model.provider(),"https://b.example/v1",model.model(),model.api());
        assertThatThrownBy(() -> service.createTransfer(request,newEndpoint)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.redeem(transfer.id(),project,newEndpoint,"READ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.createTransfer(grant(uuid(),List.of(0,5),"ANALYZE",false,false),model)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createTransfer(grant(uuid(),List.of(5),"ANALYZE",false,true),model)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.redeem(transfer.id(),project,model,"PROPOSE_PATCH")).contains("actualTotal","reportedTotal","difference");
        service.authorize(transfer.id(),project,model,"VERIFY");
        var unauthorized = new TransferInput(uuid(),report.id(),report.sourceVersion(),List.of(5),project,model.profileId(),
                request.destinationVersion(),"ANALYZE",false,false,false);
        assertThatThrownBy(() -> service.createTransfer(unauthorized,model)).isInstanceOf(IllegalArgumentException.class);
        var expired = new WorkEvidenceService(resources,json,root,Clock.offset(now,Duration.ofHours(1)));
        assertThatThrownBy(() -> expired.authorize(transfer.id(),project,model,"READ")).isInstanceOf(IllegalStateException.class);
        assertThat(service.revoke(transfer.id()).revoked()).isTrue();
        assertThat(service.createTransfer(request,model).revoked()).isTrue();
        assertThatThrownBy(() -> service.redeem(transfer.id(),project,model,"READ")).isInstanceOf(IllegalStateException.class);
    }
    @Test void independentInputValidationAndMissingSnapshotNeverProduceVerifiedData() throws Exception {
        String invalid = upload("order_id,amount\nA,not-money");
        assertThatThrownBy(() -> service.createReport(new ReportInput(uuid(),"office",invalid,"order_id","amount",decimal("1")))).isInstanceOf(IllegalArgumentException.class);
        var request = grant(uuid(),List.of(5),"ANALYZE",false,false);
        var transfer = service.createTransfer(request,model);
        Path snapshot = root.resolve(UserContext.current().id()).resolve("work-evidence/sources").resolve(report.sourceVersion()+".csv");
        Files.writeString(snapshot,"id,amount\nA,10");
        assertThatThrownBy(() -> service.redeem(transfer.id(),project,model,"READ")).hasMessageContaining("摘要不符");
        Files.delete(snapshot);
        assertThatThrownBy(() -> service.report(report.id())).hasMessageContaining("版本缺失");
    }
    @Test void controllerChecksProjectOwnershipAndResolvesTheActualModel() throws Exception {
        var coding = mock(CodingService.class);
        when(coding.projects()).thenReturn(List.of());
        var controller = new WorkEvidenceController(service,coding);
        var request = grant(uuid(),List.of(5),"ANALYZE",false,false);
        assertThatThrownBy(() -> controller.transfer(request)).hasMessageContaining("目标项目");
        verify(coding,never()).transferDestination(anyString());
        when(coding.projects()).thenReturn(List.of(new CodingService.Project(project,"real project","now")));
        when(coding.transferDestination(model.profileId())).thenReturn(model);
        assertThat(controller.transfer(request).destination()).isEqualTo(controller.destination(model.profileId()));
        assertThat(controller.reports("office",csvId)).containsExactly(report);
    }
}

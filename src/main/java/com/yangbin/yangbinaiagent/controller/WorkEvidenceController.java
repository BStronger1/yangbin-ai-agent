package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.coding.CodingService;
import com.yangbin.yangbinaiagent.work.WorkEvidenceService;
import com.yangbin.yangbinaiagent.work.WorkEvidenceService.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/ai/work/evidence")
public class WorkEvidenceController {
    private final WorkEvidenceService service;
    private final CodingService coding;

    public WorkEvidenceController(WorkEvidenceService service, CodingService coding) {
        this.service = service;
        this.coding = coding;
    }
    @PostMapping("/reports")
    public Report report(@RequestBody ReportInput input) throws IOException { return service.createReport(input); }
    @GetMapping("/reports/{id}")
    public Report get(@PathVariable String id) throws IOException { return service.report(id); }
    @GetMapping("/recent-reports")
    public List<ReportSummary> recent() throws IOException { return service.recentReports(); }
    @GetMapping("/reports")
    public List<Report> reports(@RequestParam String partnerId, @RequestParam String resourceId) throws IOException {
        return service.reports(partnerId, resourceId);
    }
    @PostMapping("/reports/{id}/verify")
    public Verification verify(@PathVariable String id, @RequestBody Claim input) throws IOException {
        return service.verify(id, input);
    }
    @GetMapping("/destination")
    public Destination destination(@RequestParam(required=false) String modelProfileId) throws IOException {
        return service.destination(coding.transferDestination(modelProfileId));
    }
    @PostMapping("/transfers")
    public Transfer transfer(@RequestBody TransferInput input) throws IOException {
        if (input == null || coding.projects().stream().noneMatch(project -> project.id().equals(input.projectId())))
            throw new IllegalArgumentException("目标项目不存在或不可访问");
        return service.createTransfer(input, coding.transferDestination(input.modelProfileId()));
    }
    @GetMapping("/transfers/{id}")
    public Transfer inspect(@PathVariable String id) throws IOException { return service.inspect(id); }
    @GetMapping("/transfers")
    public List<Transfer> transfers(@RequestParam String reportId) throws IOException { return service.transfers(reportId); }
    @PostMapping("/transfers/{id}/revoke")
    public Transfer revoke(@PathVariable String id) throws IOException { return service.revoke(id); }
}

package dev.washon.report.api;

import dev.washon.report.application.ReportService;
import dev.washon.report.payload.request.ReportRequest;
import dev.washon.report.payload.response.NeedsCheckResponse;
import dev.washon.report.payload.response.ReportResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ported from apps/api/src/reports/reports.controller.ts. Validation and the
 * needs-check computation both live in {@link ReportService}.
 */
@RestController
public class ReportApi {

    private final ReportService reportService;

    public ReportApi(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse createReport(@RequestBody ReportRequest request) {
        return reportService.createReport(request);
    }

    @GetMapping("/admin/machines/{machineId}/needs-check")
    public NeedsCheckResponse getNeedsCheck(@PathVariable String machineId) {
        return reportService.getNeedsCheck(machineId);
    }
}

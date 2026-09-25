package dev.washon.report.application;

import dev.washon.machine.infrastructure.MachineRepository;
import dev.washon.report.domain.StudentReport;
import dev.washon.report.infrastructure.StudentReportRepository;
import dev.washon.report.payload.request.ReportRequest;
import dev.washon.report.payload.response.NeedsCheckResponse;
import dev.washon.report.payload.response.ReportResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persists anonymous student reports and computes the needs-check flag other
 * stories (e.g. SB-006's admin dashboard) can reuse via
 * {@link #getNeedsCheck(String)}. Ported from apps/api/src/reports/reports.service.ts.
 */
@Service
public class ReportService {

    // Plan section 4.6 / SB-004 AC #2: 3+ distinct device_ids reporting the
    // same machine within this window flags it "needs check".
    static final Duration NEEDS_CHECK_WINDOW = Duration.ofMinutes(10);
    static final int NEEDS_CHECK_DISTINCT_DEVICE_THRESHOLD = 3;

    private final StudentReportRepository reports;
    private final MachineRepository machines;

    public ReportService(StudentReportRepository reports, MachineRepository machines) {
        this.reports = reports;
        this.machines = machines;
    }

    /**
     * No auth (plan section 2: student flow is anonymous) -- device_id is a
     * client-generated anonymous identifier, not an authenticated principal.
     */
    public ReportResponse createReport(ReportRequest request) {
        if (!StringUtils.hasText(request.machineId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "machine_id must be a non-empty string");
        }
        if (request.reportedState() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "reported_state must be a valid machine state");
        }
        if (!StringUtils.hasText(request.deviceId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device_id must be a non-empty string");
        }

        StudentReport report = new StudentReport();
        report.setMachineId(request.machineId());
        report.setReportedState(request.reportedState());
        report.setDeviceId(request.deviceId());
        reports.saveAndFlush(report);

        return new ReportResponse(report.getId(), report.getMachineId(), report.getReportedState(),
                report.getDeviceId(), report.getCreatedAt());
    }

    /**
     * Needs-check flag for the admin dashboard (SB-006) to consume: true
     * when 3 or more DISTINCT device_ids reported on this machine within the
     * last 10 minutes (from now, at call time -- not from any single
     * report). Repeated reports from the same device_id count once, via
     * {@link StudentReportRepository#countDistinctDeviceIdsSince}.
     *
     * <p>Exposed as GET /admin/machines/{machineId}/needs-check (see
     * {@link dev.washon.report.api.ReportApi}), matching the original
     * NestJS route shape, and guarded by AdminAuthInterceptor via
     * AdminWebConfig's {@code /admin/machines/**} pattern.
     */
    public NeedsCheckResponse getNeedsCheck(String machineId) {
        if (!machines.existsById(machineId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine " + machineId + " not found");
        }

        Instant windowStart = Instant.now().minus(NEEDS_CHECK_WINDOW);
        long distinctDeviceCount = reports.countDistinctDeviceIdsSince(machineId, windowStart);

        return new NeedsCheckResponse(machineId, distinctDeviceCount >= NEEDS_CHECK_DISTINCT_DEVICE_THRESHOLD,
                distinctDeviceCount, NEEDS_CHECK_WINDOW.toMinutes());
    }
}

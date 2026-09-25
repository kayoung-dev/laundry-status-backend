package dev.washon.admin.api;

import dev.washon.admin.application.AdminService;
import dev.washon.admin.payload.request.OverrideRequest;
import dev.washon.admin.payload.response.AuditEntryResponse;
import dev.washon.admin.payload.response.DashboardMachineResponse;
import dev.washon.admin.payload.response.OverrideResponse;
import dev.washon.auth.domain.AuthenticatedAdmin;
import dev.washon.common.web.AdminAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin control plane, ported from apps/api/src/admin/admin.controller.ts.
 * Every route here sits under {@code /admin/machines/**}, which
 * {@code AdminWebConfig} guards with {@link AdminAuthInterceptor} as a whole,
 * so a new route cannot be added unprotected by omission.
 */
@RestController
@RequestMapping("/admin/machines")
public class AdminApi {

    private final AdminService adminService;

    public AdminApi(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public List<DashboardMachineResponse> getDashboard() {
        return adminService.getDashboard();
    }

    @PostMapping("/{machineId}/override")
    public OverrideResponse setOverride(@PathVariable String machineId,
            @RequestBody(required = false) OverrideRequest body, HttpServletRequest request) {
        return adminService.setOverride(machineId, body != null ? body.state() : null, admin(request));
    }

    @PostMapping("/{machineId}/override/clear")
    public OverrideResponse clearOverride(@PathVariable String machineId, HttpServletRequest request) {
        return adminService.clearOverride(machineId, admin(request));
    }

    /** Audit log view: {@code ?source=admin_override} narrows it to override actions. */
    @GetMapping("/{machineId}/events")
    public List<AuditEntryResponse> getAuditLog(@PathVariable String machineId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String source) {
        return adminService.getAuditLog(machineId, limit, source);
    }

    private static AuthenticatedAdmin admin(HttpServletRequest request) {
        return AdminAuthInterceptor.currentAdmin(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing admin session"));
    }
}

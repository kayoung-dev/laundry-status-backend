package dev.washon.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AdminAuthInterceptor} on every admin route except
 * login/logout, which must stay reachable without a session:
 * {@code /admin/auth/me} (SB-005), and everything under
 * {@code /admin/machines} -- the dashboard, override set/clear and audit log
 * (SB-006, dev.washon.admin.api.AdminApi) plus SB-004's needs-check route
 * (dev.washon.report.api.ReportApi).
 *
 * <p>Also declares CORS for the frontend's origins (SB-008). The admin
 * session is an httpOnly cookie, so the frontend's {@code fetch} calls use
 * {@code credentials: "include"}; per the Fetch/CORS spec that only works
 * against an explicit {@code Access-Control-Allow-Origin} value, never a
 * wildcard, which is why this lists configurable origins
 * ({@code washon.cors.allowed-origins}, comma-separated) rather than
 * {@code "*"}. Spring echoes back whichever listed origin made the request.
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final String[] allowedOrigins;

    public AdminWebConfig(
            AdminAuthInterceptor adminAuthInterceptor,
            @Value("${washon.cors.allowed-origins:http://localhost:3000}") String[] allowedOrigins) {
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/auth/me", "/admin/machines", "/admin/machines/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}

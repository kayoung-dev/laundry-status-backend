package dev.washon.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.TestcontainersConfiguration;
import dev.washon.auth.application.SessionTokenService;
import dev.washon.auth.domain.AdminRole;
import dev.washon.auth.domain.AdminUser;
import dev.washon.auth.infrastructure.AdminUserRepository;
import dev.washon.common.crypto.ScryptUtil;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rewrites the login/logout/me scenarios from apps/api/test/admin.e2e-spec.ts
 * against dev.washon.auth.api.AdminAuthApi, using real MariaDB (Testcontainers)
 * instead of mocks. The dashboard/override scenarios in that same spec file
 * belong to SB-006 and are not covered here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminAuthApiTest {

    private static final AtomicInteger SUFFIX = new AtomicInteger();
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminUserRepository adminUsers;

    @Test
    void validLoginGrantsAccessToAGuardedRouteViaBothCookieAndBearerToken() throws Exception {
        String email = seedAdmin("happy-path");

        MvcResult login = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyString())))
                .andExpect(jsonPath("$.admin.email").value(email))
                .andReturn();

        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains(SessionTokenService.COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
        String cookieValue = extractCookieValue(setCookie);
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/admin/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        mockMvc.perform(get("/admin/auth/me").cookie(new Cookie(SessionTokenService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(get("/admin/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedOrGarbageTokenReturns401() throws Exception {
        mockMvc.perform(get("/admin/auth/me").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());

        String email = seedAdmin("tampered");
        MvcResult login = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
        String payload = token.split("\\.")[0];

        mockMvc.perform(get("/admin/auth/me").header("Authorization", "Bearer " + payload + ".deadbeef"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordAndUnknownEmailBothFailIdenticallyWith401() throws Exception {
        String email = seedAdmin("wrong-pw");

        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "not-the-password")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody-" + unique() + "@washon.test", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedLoginPayloadReturns400() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutClearsTheSessionCookie() throws Exception {
        String email = seedAdmin("logout");
        MvcResult login = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String cookieValue = extractCookieValue(login.getResponse().getHeader(HttpHeaders.SET_COOKIE));

        MvcResult logout = mockMvc.perform(post("/admin/auth/logout")
                        .cookie(new Cookie(SessionTokenService.COOKIE_NAME, cookieValue)))
                .andExpect(status().isOk())
                .andReturn();

        String clearedCookie = logout.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(clearedCookie).startsWith(SessionTokenService.COOKIE_NAME + "=;");
    }

    private String loginBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    private static String extractCookieValue(String setCookieHeader) {
        String firstPart = setCookieHeader.split(";", 2)[0];
        return firstPart.substring(firstPart.indexOf('=') + 1);
    }

    private String seedAdmin(String label) {
        String suffix = label + "-" + unique();
        AdminUser admin = new AdminUser();
        admin.setEmail("admin-" + suffix + "@washon.test");
        admin.setPasswordHash(ScryptUtil.hashSecret(PASSWORD));
        admin.setRole(AdminRole.super_admin);
        adminUsers.saveAndFlush(admin);
        return admin.getEmail();
    }

    private static String unique() {
        return System.nanoTime() + "-" + SUFFIX.incrementAndGet();
    }
}

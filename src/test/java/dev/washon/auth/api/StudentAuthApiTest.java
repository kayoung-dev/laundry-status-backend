package dev.washon.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.TestcontainersConfiguration;
import dev.washon.auth.application.SessionTokenService;
import dev.washon.auth.application.StudentSessionService;
import dev.washon.auth.domain.AdminRole;
import dev.washon.auth.domain.AdminUser;
import dev.washon.auth.domain.StudentUser;
import dev.washon.auth.infrastructure.AdminUserRepository;
import dev.washon.auth.infrastructure.StudentUserRepository;
import dev.washon.common.crypto.ScryptUtil;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudentAuthApiTest {

    private static final AtomicInteger SUFFIX = new AtomicInteger();
    private static final String PASSWORD = "laundry2026";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StudentUserRepository users;
    @Autowired AdminUserRepository admins;

    @Test
    void loginSetsASessionCookieThatMeAccepts() throws Exception {
        String email = seedStudent();

        MvcResult login = login(email.toUpperCase(), PASSWORD, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.name").value("김세탁"))
                .andExpect(jsonPath("$.user.hall").value("비전관"))
                .andReturn();

        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(StudentSessionService.COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly").containsIgnoringCase("SameSite=Lax").contains("Max-Age=2592000");

        mockMvc.perform(get("/auth/me").cookie(new Cookie(StudentSessionService.COOKIE_NAME, cookieValue(setCookie))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void withoutRememberTheCookieEndsWithTheBrowserSession() throws Exception {
        String email = seedStudent();
        String setCookie = login(email, PASSWORD, false).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).doesNotContain("Max-Age").doesNotContain("Expires");
    }

    @Test
    void wrongPasswordAndUnknownEmailAreBoth401() throws Exception {
        String email = seedStudent();
        login(email, "wrong-password1", false).andExpect(status().isUnauthorized());
        login("nobody-" + SUFFIX.incrementAndGet() + "@gmail.com", PASSWORD, false).andExpect(status().isUnauthorized());
        postJson(Map.of("email", email)).andExpect(status().isBadRequest());
    }

    @Test
    void tooManyAttemptsGet429() throws Exception {
        String email = seedStudent();
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password1", false).andExpect(status().isUnauthorized());
        }
        login(email, PASSWORD, false).andExpect(status().isTooManyRequests());
    }

    @Test
    void meNeedsAValidStudentSession() throws Exception {
        mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer garbage.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminAndStudentTokensDoNotCrossOver() throws Exception {
        AdminUser admin = new AdminUser();
        admin.setEmail("admin-" + System.nanoTime() + "@washon.test");
        admin.setPasswordHash(ScryptUtil.hashSecret(PASSWORD));
        admin.setRole(AdminRole.site_admin);
        admins.saveAndFlush(admin);
        String adminToken = objectMapper.readTree(mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", admin.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();

        String studentToken = objectMapper.readTree(login(seedStudent(), PASSWORD, false)
                .andReturn().getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/auth/me").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/auth/me").cookie(new Cookie(SessionTokenService.COOKIE_NAME, studentToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsTheCookie() throws Exception {
        String setCookie = mockMvc.perform(post("/auth/logout")).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(StudentSessionService.COOKIE_NAME + "=;").contains("Max-Age=0");
    }

    private String seedStudent() {
        StudentUser user = new StudentUser();
        user.setEmail("student-" + System.nanoTime() + "-" + SUFFIX.incrementAndGet() + "@handong.ac.kr");
        user.setPasswordHash(ScryptUtil.hashSecret(PASSWORD));
        user.setName("김세탁");
        user.setHall("비전관");
        user.setTermsAgreedAt(Instant.now());
        users.saveAndFlush(user);
        return user.getEmail();
    }

    private ResultActions login(String email, String password, boolean remember) throws Exception {
        return postJson(Map.of("email", email, "password", password, "remember", remember));
    }

    private ResultActions postJson(Object body) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static String cookieValue(String setCookie) {
        String first = setCookie.split(";", 2)[0];
        return first.substring(first.indexOf('=') + 1);
    }
}

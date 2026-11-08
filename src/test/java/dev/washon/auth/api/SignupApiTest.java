package dev.washon.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.TestcontainersConfiguration;
import dev.washon.auth.application.SignupCodeMailer;
import dev.washon.auth.domain.StudentUser;
import dev.washon.auth.infrastructure.SignupCodeRepository;
import dev.washon.auth.infrastructure.StudentUserRepository;
import dev.washon.common.crypto.ScryptUtil;
import dev.washon.facility.domain.Room;
import dev.washon.facility.domain.Site;
import dev.washon.facility.domain.Tenant;
import dev.washon.facility.infrastructure.RoomRepository;
import dev.washon.facility.infrastructure.SiteRepository;
import dev.washon.facility.infrastructure.TenantRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SignupApiTest {

    private static final AtomicInteger SUFFIX = new AtomicInteger();
    private static final String PASSWORD = "laundry2026";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StudentUserRepository users;
    @Autowired SignupCodeRepository codes;
    @Autowired TenantRepository tenants;
    @Autowired SiteRepository sites;
    @Autowired RoomRepository rooms;
    @MockitoBean SignupCodeMailer mailer;

    private String roomId;

    @BeforeEach
    void seedRoom() {
        Tenant tenant = new Tenant();
        tenant.setName("t");
        tenants.saveAndFlush(tenant);
        Site site = new Site();
        site.setTenantId(tenant.getId());
        site.setName("s");
        sites.saveAndFlush(site);
        Room room = new Room();
        room.setSiteId(site.getId());
        room.setName("r");
        rooms.saveAndFlush(room);
        roomId = room.getId();
    }

    @Test
    void codeThenSignupCreatesTheAccount() throws Exception {
        String email = uniqueEmail("handong.ac.kr");
        String code = sendCode(email);

        signup(body(email.toUpperCase(), code))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("김세탁"))
                .andExpect(jsonPath("$.defaultRoomId").value(roomId));

        StudentUser user = users.findAll().stream().filter(u -> u.getEmail().equals(email)).findFirst().orElseThrow();
        assertThat(ScryptUtil.verifySecret(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(user.getHall()).isEqualTo("비전관");
        assertThat(user.getFloor()).isEqualTo("3");
        assertThat(user.getRoomNumber()).isEqualTo("312");
        assertThat(user.isPushAgreed()).isTrue();
        assertThat(codes.existsById(email)).isFalse();
    }

    @Test
    void gmailMayAlsoSignUp() throws Exception {
        String email = uniqueEmail("gmail.com");
        signup(body(email, sendCode(email))).andExpect(status().isCreated());
    }

    @Test
    void otherDomainsAreRejected() throws Exception {
        for (String email : new String[] {"a@naver.com", "a@handong.edu", "a@sub.handong.ac.kr", "not-an-email"}) {
            postJson("/auth/signup/code", Map.of("email", email)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void registeredEmailGets409() throws Exception {
        String email = uniqueEmail("gmail.com");
        signup(body(email, sendCode(email))).andExpect(status().isCreated());

        postJson("/auth/signup/code", Map.of("email", email)).andExpect(status().isConflict());
    }

    @Test
    void secondCodeTooSoonGets429() throws Exception {
        String email = uniqueEmail("gmail.com");
        sendCode(email);
        postJson("/auth/signup/code", Map.of("email", email)).andExpect(status().isTooManyRequests());
    }

    @Test
    void wrongCodeGets422AndTooManyWrongCodesBurnTheCode() throws Exception {
        String email = uniqueEmail("handong.ac.kr");
        String code = sendCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            signup(body(email, wrong)).andExpect(status().isUnprocessableEntity());
        }
        // The right code no longer works after five misses.
        signup(body(email, code)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void verifyChecksTheCodeWithoutUsingItUp() throws Exception {
        String email = uniqueEmail("handong.ac.kr");
        String code = sendCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        postJson("/auth/signup/code/verify", Map.of("email", email, "code", wrong))
                .andExpect(status().isUnprocessableEntity());
        postJson("/auth/signup/code/verify", Map.of("email", email.toUpperCase(), "code", code))
                .andExpect(status().isNoContent());
        postJson("/auth/signup/code/verify", Map.of("email", email, "code", code))
                .andExpect(status().isNoContent());

        signup(body(email, code)).andExpect(status().isCreated());
        // Used up by the sign-up.
        postJson("/auth/signup/code/verify", Map.of("email", email, "code", code))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void wrongVerifiesCountTowardTheAttemptLimit() throws Exception {
        String email = uniqueEmail("gmail.com");
        String code = sendCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            postJson("/auth/signup/code/verify", Map.of("email", email, "code", wrong))
                    .andExpect(status().isUnprocessableEntity());
        }
        postJson("/auth/signup/code/verify", Map.of("email", email, "code", code))
                .andExpect(status().isUnprocessableEntity());
        postJson("/auth/signup/code/verify", Map.of("email", "a@naver.com", "code", code))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signupWithoutACodeGets422() throws Exception {
        signup(body(uniqueEmail("gmail.com"), "123456")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidFieldsGet400() throws Exception {
        String email = uniqueEmail("gmail.com");
        String code = sendCode(email);

        signup(with(body(email, code), "password", "short1")).andExpect(status().isBadRequest());
        signup(with(body(email, code), "password", "onlyletters")).andExpect(status().isBadRequest());
        signup(with(body(email, code), "name", " ")).andExpect(status().isBadRequest());
        signup(with(body(email, code), "hall", "")).andExpect(status().isBadRequest());
        signup(with(body(email, code), "floor", "3층")).andExpect(status().isBadRequest());
        signup(with(body(email, code), "defaultRoomId", "no-such-room")).andExpect(status().isBadRequest());
        signup(with(body(email, code), "agreements", Map.of("tos", true, "privacy", false, "push", false)))
                .andExpect(status().isBadRequest());

        // None of those used up the code.
        signup(body(email, code)).andExpect(status().isCreated());
    }

    private String sendCode(String email) throws Exception {
        postJson("/auth/signup/code", Map.of("email", email)).andExpect(status().isNoContent());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(email), code.capture());
        assertThat(code.getValue()).matches("\\d{6}");
        return code.getValue();
    }

    private Map<String, Object> body(String email, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("code", code);
        body.put("password", PASSWORD);
        body.put("name", "김세탁");
        body.put("hall", "비전관");
        body.put("floor", "3");
        body.put("roomNumber", "312");
        body.put("defaultRoomId", roomId);
        body.put("agreements", Map.of("tos", true, "privacy", true, "push", true));
        return body;
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        body.put(key, value);
        return body;
    }

    private ResultActions signup(Map<String, Object> body) throws Exception {
        return postJson("/auth/signup", body);
    }

    private ResultActions postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static String uniqueEmail(String domain) {
        return "student-" + System.nanoTime() + "-" + SUFFIX.incrementAndGet() + "@" + domain;
    }
}

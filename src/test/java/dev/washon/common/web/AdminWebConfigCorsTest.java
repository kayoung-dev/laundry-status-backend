package dev.washon.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.washon.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** washon.cors.allowed-origins takes several comma-separated origins (e.g. the dev site plus localhost). */
@SpringBootTest(properties = "washon.cors.allowed-origins=https://dev.washon.site,http://localhost:3000")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminWebConfigCorsTest {

    @Autowired MockMvc mockMvc;

    @Test
    void allowsEachConfiguredOriginWithCredentials() throws Exception {
        for (String origin : new String[] {"https://dev.washon.site", "http://localhost:3000"}) {
            mockMvc.perform(preflight(origin))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }
    }

    @Test
    void rejectsAnOriginThatIsNotListed() throws Exception {
        mockMvc.perform(preflight("https://evil.example"))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.RequestBuilder preflight(String origin) {
        return options("/admin/auth/login")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST");
    }
}

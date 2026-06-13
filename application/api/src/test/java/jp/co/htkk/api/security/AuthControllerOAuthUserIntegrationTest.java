package jp.co.htkk.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerOAuthUserIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void oauthOnlyUser_cannotPasswordLogin_returns401() throws Exception {
        // user_id=3 (oauth@example.com) has password='' — any password must be rejected
        mockMvc.perform(post("/auth/login").servletPath("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"oauth@example.com\",\"password\":\"\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/login").servletPath("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"oauth@example.com\",\"password\":\"anything\"}"))
                .andExpect(status().isUnauthorized());
    }
}

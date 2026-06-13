package jp.co.htkk.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jp.co.htkk.security.jwt.JwtTokenService;
import jp.co.htkk.security.web.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenService tokenService;

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/auth/login").servletPath("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/auth/login").servletPath("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"WRONG\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_noToken_returns401() throws Exception {
        mockMvc.perform(get("/admin/users").servletPath("/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_garbageToken_returns401() throws Exception {
        mockMvc.perform(get("/admin/users").servletPath("/admin/users").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void normalUser_cannotWrite_butCanRead() throws Exception {
        String token = login("normal", "user123");
        mockMvc.perform(post("/admin/users").servletPath("/admin/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"email\":\"x@example.com\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users").servletPath("/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void admin_canCreate_andAuditUidIsAdmin() throws Exception {
        String token = login("admin", "admin123");
        MvcResult created = mockMvc.perform(post("/admin/users").servletPath("/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"created-by-admin\",\"email\":\"c@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").exists())
                .andReturn();
        long newId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("userId").asLong();
        mockMvc.perform(get("/admin/users/" + newId).servletPath("/admin/users/" + newId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("created-by-admin"));
        // the JWT filter populated LoginInfo with admin's uid (1), so AuditInterceptor stamped created_by = 1
        Long createdBy = jdbc.queryForObject("SELECT created_by FROM users WHERE user_id = ?", Long.class, newId);
        assertThat(createdBy).isEqualTo(1L);
    }

    @Test
    void whitelist_swaggerAndHealth_noTokenNeeded() throws Exception {
        mockMvc.perform(get("/actuator/health").servletPath("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api-docs").servletPath("/api-docs")).andExpect(status().isOk());
    }

    @Test
    void tokenWithinRenewWindow_getsNewTokenHeader() throws Exception {
        // mint a token with 1 minute TTL (inside the 3m renew-window)
        String shortToken = tokenService.issueWithTtl(1L, "admin",
                Set.of("ADMIN"), Set.of("USER_READ", "USER_WRITE"), Duration.ofMinutes(1));
        mockMvc.perform(get("/admin/users").servletPath("/admin/users").header("Authorization", "Bearer " + shortToken))
                .andExpect(status().isOk())
                .andExpect(header().exists(JwtAuthenticationFilter.RENEW_HEADER));
    }
}

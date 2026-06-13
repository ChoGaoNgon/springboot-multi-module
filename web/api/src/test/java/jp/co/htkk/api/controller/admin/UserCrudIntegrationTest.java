package jp.co.htkk.api.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserCrudIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Admin (seed uid 1) holds USER_READ + USER_WRITE — required now that the app is secured. */
    private String adminToken() throws Exception {
        MvcResult r = mockMvc.perform(post("/auth/login").servletPath("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }

    @Test
    void createThenGetThenList() throws Exception {
        String token = adminToken();

        // create
        MvcResult created = mockMvc.perform(post("/admin/users")
                        .servletPath("/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").exists())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.email").value("alice@example.com"))
                .andReturn();

        long userId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("userId").asLong();

        // get by id
        mockMvc.perform(get("/admin/users/" + userId).servletPath("/admin/users/" + userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value((int) userId))
                .andExpect(jsonPath("$.data.username").value("alice"));

        // list (admin + normal seed + alice => at least 3); data stays an array, page metadata alongside
        mockMvc.perform(get("/admin/users").servletPath("/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.page.total").value(Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.page.pageNum").value(1))
                .andExpect(jsonPath("$.page.pageSize").value(10));

        // paginated: pageSize=1 returns exactly one row, but total still reflects all active users
        mockMvc.perform(get("/admin/users?pageNum=1&pageSize=1").servletPath("/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.page.pageSize").value(1))
                .andExpect(jsonPath("$.page.total").value(Matchers.greaterThanOrEqualTo(3)));
    }
}

package jp.co.htkk.api.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardMonthlyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void monthlyPoint_aggregatesNonDeletedRowsForSelectedMonth() throws Exception {
        mockMvc.perform(get("/admin/dashboard/monthly")
                        .servletPath("/admin/dashboard/monthly")
                        .param("monthSelected", "202212"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.earnPoint.stepPoint").value(300))
                .andExpect(jsonPath("$.data.earnPoint.pointEvent").value(70))
                .andExpect(jsonPath("$.data.usedPoint.payPayPoint").value(35))
                .andExpect(jsonPath("$.data.usedPoint.mallPoint").value(0))
                .andExpect(jsonPath("$.data.revocationPoint").value(12));
    }
}

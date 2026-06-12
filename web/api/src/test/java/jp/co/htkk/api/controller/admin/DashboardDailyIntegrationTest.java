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

/**
 * Exercises the daily-point endpoint end-to-end. Its service layer runs
 * CustomTransactionPointMapper.getTotalPointLessThanDateAndStatusAndType, whose SQL uses
 * the MySQL DATE() function. This test verifies that path works on H2 (MODE=MySQL) after
 * the Boot 3 upgrade. If H2 ever rejects DATE(), switch this test to Testcontainers MySQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardDailyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dailyPoint_runsDateFunctionPath_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/dashboard/daily")
                        .servletPath("/admin/dashboard/daily")
                        .param("dateSelected", "20221231"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
    }
}

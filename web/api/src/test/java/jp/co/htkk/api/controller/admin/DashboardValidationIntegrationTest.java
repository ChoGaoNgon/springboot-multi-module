package jp.co.htkk.api.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void monthlyPoint_invalidMonth_returnsBadRequestNotServerError() throws Exception {
        // "abc" violates @DateFormat(YYYYMM); must yield a clean 400 from BindExceptionHandler,
        // NOT a 500 NPE. If this returns 500, the MessageService injection bug is real.
        mockMvc.perform(get("/admin/dashboard/monthly")
                        .servletPath("/admin/dashboard/monthly")
                        .param("monthSelected", "abc"))
                .andExpect(status().isBadRequest());
    }
}

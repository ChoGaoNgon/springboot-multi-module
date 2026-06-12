package jp.co.htkk.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApiContextLoadsTest {

    @Test
    void contextLoads() {
        // Passes if the full application context boots on H2 under the "test" profile.
    }
}

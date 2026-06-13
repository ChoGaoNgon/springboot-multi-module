package jp.co.htkk.batch;

import jp.co.htkk.batch.event.BatchJobLauncher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the batch application context boots on Boot 3 / Java 21 against H2.
 * BatchJobLauncher is an ApplicationRunner that requires a {@code batch_id} and calls
 * System.exit on an invalid id; it is mocked here so startup does not run a real job.
 */
@SpringBootTest
@ActiveProfiles("test")
class BatchContextLoadsTest {

    @MockBean
    private BatchJobLauncher batchJobLauncher;

    @Test
    void contextLoads() {
    }
}

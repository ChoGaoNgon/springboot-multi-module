package jp.co.htkk.api.security;

import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.port.GoogleUserSyncService;
import jp.co.htkk.security.port.SecurityUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GoogleUserSyncServiceImplIntegrationTest {

    @Autowired private GoogleUserSyncService syncService;
    @Autowired private JdbcTemplate jdbc;

    private GoogleUserInfo info(String sub, String email) {
        return GoogleUserInfo.builder().sub(sub).email(email).emailVerified(true).name("X").build();
    }

    @Test
    void matchByGoogleSub_returnsExistingUser() {
        // user_id=3 seeded with google_sub=999000111
        SecurityUser u = syncService.syncFromGoogle(info("999000111", "oauth@example.com"));
        assertThat(u.getUid()).isEqualTo(3L);
        assertThat(u.getUsername()).isEqualTo("oauth@example.com");
        assertThat(u.getRoles()).contains("USER");
    }

    @Test
    void linkByEmail_setsGoogleSubOnExistingUser() {
        // admin (user_id=1) has no google_sub; link by email
        SecurityUser u = syncService.syncFromGoogle(info("sub-admin-link", "admin@example.com"));
        assertThat(u.getUid()).isEqualTo(1L);
        String linked = jdbc.queryForObject("SELECT google_sub FROM users WHERE user_id = 1", String.class);
        assertThat(linked).isEqualTo("sub-admin-link");
        assertThat(u.getRoles()).contains("ADMIN");
    }

    @Test
    void createNew_insertsUserAndDefaultRole() {
        SecurityUser u = syncService.syncFromGoogle(info("sub-brand-new", "newbie@gmail.com"));
        assertThat(u.getUid()).isNotNull();
        assertThat(u.getUsername()).isEqualTo("newbie@gmail.com");
        assertThat(u.getRoles()).containsExactly("USER");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE google_sub = 'sub-brand-new' AND password = ''", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}

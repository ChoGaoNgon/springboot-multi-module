package jp.co.htkk.security.jwt;

import jp.co.htkk.security.config.SecurityModuleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {

    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        SecurityModuleProperties props = new SecurityModuleProperties();
        props.getJwt().setSecret("01234567890123456789012345678901234567890123456789"); // >= 32 bytes
        props.getJwt().setExpiration(Duration.ofMinutes(30));
        props.getJwt().setRenewWindow(Duration.ofMinutes(3));
        service = new JwtTokenService(props);
    }

    @Test
    void issueThenParse_roundTrips() {
        String token = service.issue(7L, "alice", Set.of("ADMIN"), Set.of("USER_READ", "USER_WRITE"));
        JwtPrincipal p = service.parse(token);
        assertThat(p.getUid()).isEqualTo(7L);
        assertThat(p.getUsername()).isEqualTo("alice");
        assertThat(p.getRoles()).containsExactly("ADMIN");
        assertThat(p.getPermissions()).containsExactlyInAnyOrder("USER_READ", "USER_WRITE");
    }

    @Test
    void parse_invalidToken_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.parse("not.a.jwt"))
                .isInstanceOf(JwtTokenService.InvalidTokenException.class);
    }

    @Test
    void renewIfNeeded_returnsNewTokenOnlyWhenWithinWindow() {
        // token already inside the renew window (1 min left, window is 3 min)
        String almostExpired = service.issueWithTtl(7L, "alice", Set.of("ADMIN"), Set.of("USER_READ"), Duration.ofMinutes(1));
        assertThat(service.renewIfNeeded(almostExpired)).isPresent();

        String fresh = service.issue(7L, "alice", Set.of("ADMIN"), Set.of("USER_READ"));
        assertThat(service.renewIfNeeded(fresh)).isEmpty();
    }
}

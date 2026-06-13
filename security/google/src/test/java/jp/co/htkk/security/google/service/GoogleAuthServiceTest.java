package jp.co.htkk.security.google.service;

import jp.co.htkk.security.config.SecurityModuleProperties;
import jp.co.htkk.security.google.config.GoogleOAuthProperties;
import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.port.GoogleUserSyncService;
import jp.co.htkk.security.jwt.JwtTokenService;
import jp.co.htkk.security.port.SecurityUser;
import jp.co.htkk.security.web.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleAuthServiceTest {

    private GoogleTokenClient tokenClient;
    private GoogleIdTokenVerifier verifier;
    private GoogleUserSyncService syncService;
    private JwtTokenService jwtTokenService;
    private GoogleOAuthProperties googleProps;
    private SecurityModuleProperties securityProps;
    private GoogleAuthService service;

    @BeforeEach
    void setUp() {
        tokenClient = mock(GoogleTokenClient.class);
        verifier = mock(GoogleIdTokenVerifier.class);
        syncService = mock(GoogleUserSyncService.class);
        googleProps = new GoogleOAuthProperties();
        googleProps.setAllowedRedirectUris(List.of("https://app/oauth/callback"));
        securityProps = new SecurityModuleProperties();
        securityProps.getJwt().setSecret("test-secret-0123456789-0123456789-0123456789");
        jwtTokenService = new JwtTokenService(securityProps);
        service = new GoogleAuthService(tokenClient, verifier, syncService, jwtTokenService, googleProps, securityProps);
    }

    private GoogleTokenResponse tokens() {
        GoogleTokenResponse r = new GoogleTokenResponse();
        r.setIdToken("idtok");
        return r;
    }

    @Test
    void handleCallback_happyPath_issuesJwt() {
        when(tokenClient.exchange("code", "https://app/oauth/callback")).thenReturn(tokens());
        when(verifier.verify("idtok")).thenReturn(GoogleUserInfo.builder()
                .sub("110169").email("u@gmail.com").emailVerified(true).name("U").build());
        when(syncService.syncFromGoogle(any())).thenReturn(SecurityUser.builder()
                .uid(101L).username("u@gmail.com").passwordHash("").enabled(true)
                .roles(Set.of("USER")).permissions(Set.of("USER_READ")).build());

        LoginResponse res = service.handleCallback("code", "https://app/oauth/callback");

        assertThat(res.getAccessToken()).isNotBlank();
        assertThat(res.getTokenType()).isEqualTo("Bearer");
        assertThat(res.getExpiresIn()).isEqualTo(securityProps.getJwt().getExpiration().toSeconds());
    }

    @Test
    void handleCallback_redirectUriNotWhitelisted_throws() {
        assertThatThrownBy(() -> service.handleCallback("code", "https://evil/callback"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void handleCallback_emailNotVerified_throws() {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(verifier.verify("idtok")).thenReturn(GoogleUserInfo.builder()
                .sub("110169").email("u@gmail.com").emailVerified(false).build());

        assertThatThrownBy(() -> service.handleCallback("code", "https://app/oauth/callback"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void handleCallback_userDisabled_throws() {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(verifier.verify("idtok")).thenReturn(GoogleUserInfo.builder()
                .sub("110169").email("u@gmail.com").emailVerified(true).build());
        when(syncService.syncFromGoogle(any())).thenReturn(SecurityUser.builder()
                .uid(101L).username("u@gmail.com").passwordHash("").enabled(false)
                .roles(Set.of()).permissions(Set.of()).build());

        assertThatThrownBy(() -> service.handleCallback("code", "https://app/oauth/callback"))
                .isInstanceOf(BadCredentialsException.class);
    }
}

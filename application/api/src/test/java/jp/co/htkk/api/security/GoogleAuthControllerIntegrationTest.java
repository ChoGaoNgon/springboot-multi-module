package jp.co.htkk.api.security;

import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.service.GoogleIdTokenVerifier;
import jp.co.htkk.security.google.service.GoogleTokenClient;
import jp.co.htkk.security.google.service.GoogleTokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleAuthControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private GoogleTokenClient tokenClient;
    @MockBean private GoogleIdTokenVerifier idTokenVerifier;

    private GoogleTokenResponse tokens() {
        GoogleTokenResponse r = new GoogleTokenResponse();
        r.setIdToken("idtok");
        return r;
    }

    private String body(String code, String redirectUri) {
        return "{\"code\":\"" + code + "\",\"redirectUri\":\"" + redirectUri + "\"}";
    }

    @Test
    void callback_happyPath_returns200WithJwt() throws Exception {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(idTokenVerifier.verify("idtok")).thenReturn(GoogleUserInfo.builder()
                .sub("999000111").email("oauth@example.com").emailVerified(true).name("X").build());

        mockMvc.perform(post("/auth/google/callback").servletPath("/auth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("code", "https://app/oauth/callback")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void callback_redirectUriNotWhitelisted_returns401() throws Exception {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(idTokenVerifier.verify(anyString())).thenReturn(GoogleUserInfo.builder()
                .sub("999000111").email("oauth@example.com").emailVerified(true).build());

        mockMvc.perform(post("/auth/google/callback").servletPath("/auth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("code", "https://evil/callback")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callback_invalidIdToken_returns401() throws Exception {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(idTokenVerifier.verify(anyString())).thenThrow(new BadCredentialsException("bad"));

        mockMvc.perform(post("/auth/google/callback").servletPath("/auth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("code", "https://app/oauth/callback")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callback_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/auth/google/callback").servletPath("/auth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"redirectUri\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}

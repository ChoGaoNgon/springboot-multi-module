package jp.co.htkk.security.google.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.json.webtoken.JsonWebSignature;
import jp.co.htkk.security.google.port.GoogleUserInfo;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleIdTokenVerifierTest {

    // Build a REAL GoogleIdToken (its getPayload() is final, so it cannot be mocked).
    private GoogleIdToken tokenWith(String sub, String email, boolean emailVerified, String name) {
        JsonWebSignature.Header header = new JsonWebSignature.Header().setAlgorithm("RS256");
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject(sub);
        payload.setEmail(email);
        payload.setEmailVerified(emailVerified);
        payload.set("name", name);
        return new GoogleIdToken(header, payload, new byte[0], new byte[0]);
    }

    @Test
    void verify_validToken_mapsClaims() throws Exception {
        com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier delegate =
                mock(com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier.class);
        when(delegate.verify("good")).thenReturn(tokenWith("110169", "u@gmail.com", true, "User N"));
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(delegate);

        GoogleUserInfo info = verifier.verify("good");

        assertThat(info.getSub()).isEqualTo("110169");
        assertThat(info.getEmail()).isEqualTo("u@gmail.com");
        assertThat(info.isEmailVerified()).isTrue();
        assertThat(info.getName()).isEqualTo("User N");
    }

    @Test
    void verify_invalidToken_delegateReturnsNull_throws() throws Exception {
        com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier delegate =
                mock(com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier.class);
        when(delegate.verify("bad")).thenReturn(null);
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(delegate);

        assertThatThrownBy(() -> verifier.verify("bad")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void verify_delegateThrowsIOException_throwsBadCredentials() throws Exception {
        com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier delegate =
                mock(com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier.class);
        when(delegate.verify("io")).thenThrow(new IOException("jwks down"));
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(delegate);

        assertThatThrownBy(() -> verifier.verify("io")).isInstanceOf(BadCredentialsException.class);
    }
}

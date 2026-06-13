package jp.co.htkk.security.google.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import jp.co.htkk.security.google.port.GoogleUserInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Verifies a Google {@code id_token} (signature, iss, aud, exp) via the official google-api-client
 * verifier, then maps the payload to {@link GoogleUserInfo}. The heavy SDK verifier is injected so
 * this class stays unit-testable.
 */
@Slf4j
public class GoogleIdTokenVerifier {

    private final com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier delegate;

    public GoogleIdTokenVerifier(com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier delegate) {
        this.delegate = delegate;
    }

    public GoogleUserInfo verify(String idTokenString) {
        try {
            GoogleIdToken token = delegate.verify(idTokenString);
            if (token == null) {
                throw new BadCredentialsException("Invalid Google id_token");
            }
            GoogleIdToken.Payload payload = token.getPayload();
            return GoogleUserInfo.builder()
                    .sub(payload.getSubject())
                    .email(payload.getEmail())
                    .emailVerified(Boolean.TRUE.equals(payload.getEmailVerified()))
                    .name((String) payload.get("name"))
                    .picture((String) payload.get("picture"))
                    .build();
        } catch (IOException | GeneralSecurityException e) {
            log.error("Google id_token verify error", e);
            throw new BadCredentialsException("Failed to verify Google id_token");
        }
    }
}

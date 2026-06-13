package jp.co.htkk.security.google.service;

import jp.co.htkk.security.google.config.GoogleOAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
public class GoogleTokenClient {

    private final RestClient restClient;
    private final GoogleOAuthProperties props;

    public GoogleTokenClient(RestClient.Builder builder, GoogleOAuthProperties props) {
        this.props = props;
        this.restClient = builder.baseUrl(props.getTokenEndpoint()).build();
    }

    public GoogleTokenResponse exchange(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        try {
            return restClient.post()
                    .uri("/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // invalid / expired / reused code
                log.warn("Google token exchange rejected (4xx): {}", e.getResponseBodyAsString());
                throw new BadCredentialsException("Google authorization failed");
            }
            log.error("Google token endpoint returned {}", e.getStatusCode(), e);
            throw new GoogleAuthException("Google service unavailable", e);
        } catch (Exception e) {
            log.error("Google token endpoint error", e);
            throw new GoogleAuthException("Google service unavailable", e);
        }
    }
}

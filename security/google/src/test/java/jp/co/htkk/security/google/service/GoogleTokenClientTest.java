package jp.co.htkk.security.google.service;

import jp.co.htkk.security.google.config.GoogleOAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleTokenClientTest {

    private GoogleOAuthProperties props;
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private GoogleTokenClient client;

    @BeforeEach
    void setUp() {
        props = new GoogleOAuthProperties();
        props.setClientId("client-123");
        props.setClientSecret("secret-xyz");
        props.setTokenEndpoint("https://oauth2.googleapis.com");
        builder = RestClient.builder().baseUrl(props.getTokenEndpoint());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleTokenClient(builder, props);
    }

    @Test
    void exchange_success_parsesTokens() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"at\",\"id_token\":\"idtok\",\"expires_in\":3599,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        GoogleTokenResponse res = client.exchange("auth-code", "https://app/oauth/callback");

        assertThat(res.getIdToken()).isEqualTo("idtok");
        assertThat(res.getAccessToken()).isEqualTo("at");
        server.verify();
    }

    @Test
    void exchange_4xx_throwsBadCredentials() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchange("bad-code", "https://app/oauth/callback"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void exchange_5xx_throwsGoogleAuthException() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.exchange("code", "https://app/oauth/callback"))
                .isInstanceOf(GoogleAuthException.class);
    }
}

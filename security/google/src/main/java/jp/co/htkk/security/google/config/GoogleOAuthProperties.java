package jp.co.htkk.security.google.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.security.oauth.google")
public class GoogleOAuthProperties {
    private boolean enabled = true;
    private String clientId;
    private String clientSecret;
    /** Token endpoint base URL; overridable in tests. */
    private String tokenEndpoint = "https://oauth2.googleapis.com";
    private List<String> allowedRedirectUris = new ArrayList<>();
    private String defaultRoleCode = "USER";
    private Duration httpTimeout = Duration.ofSeconds(5);
}

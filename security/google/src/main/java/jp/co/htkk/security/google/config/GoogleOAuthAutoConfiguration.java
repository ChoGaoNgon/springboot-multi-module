package jp.co.htkk.security.google.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier.Builder;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jp.co.htkk.security.config.SecurityModuleAutoConfiguration;
import jp.co.htkk.security.config.SecurityModuleProperties;
import jp.co.htkk.security.google.port.GoogleUserSyncService;
import jp.co.htkk.security.google.service.GoogleAuthService;
import jp.co.htkk.security.google.service.GoogleIdTokenVerifier;
import jp.co.htkk.security.google.service.GoogleTokenClient;
import jp.co.htkk.security.jwt.JwtTokenService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

import java.util.List;

@AutoConfiguration(after = SecurityModuleAutoConfiguration.class)
@ConditionalOnProperty(prefix = "app.security.oauth.google", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GoogleOAuthProperties.class)
public class GoogleOAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GoogleTokenClient googleTokenClient(GoogleOAuthProperties props) {
        return new GoogleTokenClient(RestClient.builder(), props);
    }

    @Bean
    @ConditionalOnMissingBean
    public GoogleIdTokenVerifier googleIdTokenVerifier(GoogleOAuthProperties props) {
        com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier delegate =
                new Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                        .setAudience(List.of(props.getClientId()))
                        .setIssuers(List.of("accounts.google.com", "https://accounts.google.com"))
                        .build();
        return new GoogleIdTokenVerifier(delegate);
    }

    @Bean
    @ConditionalOnMissingBean
    public GoogleAuthService googleAuthService(GoogleTokenClient client,
                                               GoogleIdTokenVerifier verifier,
                                               GoogleUserSyncService userSyncService,
                                               JwtTokenService tokenService,
                                               GoogleOAuthProperties googleProps,
                                               SecurityModuleProperties securityProps) {
        return new GoogleAuthService(client, verifier, userSyncService, tokenService, googleProps, securityProps);
    }

    // GoogleAuthController is a @RestController picked up by the consuming app's component scan.
    // The app must provide a GoogleUserSyncService bean (the module's contract).
}

package jp.co.htkk.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.security")
public class SecurityModuleProperties {

    private boolean enabled = true;
    private Jwt jwt = new Jwt();
    /** Extra public paths added to the built-in whitelist. */
    private List<String> publicPaths = new ArrayList<>();

    @Data
    public static class Jwt {
        private String secret;
        private Duration expiration = Duration.ofMinutes(30);
        private Duration renewWindow = Duration.ofMinutes(3);
    }
}

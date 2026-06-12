package jp.co.htkk.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jp.co.htkk.security.jwt.JwtTokenService;
import jp.co.htkk.security.web.JwtAuthenticationFilter;
import jp.co.htkk.security.web.RestAccessDeniedHandler;
import jp.co.htkk.security.web.RestAuthenticationEntryPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SecurityModuleProperties.class)
@EnableMethodSecurity
public class SecurityModuleAutoConfiguration {
    // @AutoConfiguration (not @Configuration) so Boot's AutoConfigurationExcludeFilter
    // keeps the app's broad scanBasePackages="jp.co.htkk" from registering it twice.

    private static final String[] DEFAULT_PUBLIC_PATHS = {
            "/auth/login", "/swagger-ui/**", "/swagger-ui.html",
            "/api-docs/**", "/v3/api-docs/**", "/actuator/health"
    };

    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenService jwtTokenService(SecurityModuleProperties props) {
        return new JwtTokenService(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new RestAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return new RestAccessDeniedHandler(objectMapper);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenService tokenService) {
        return new JwtAuthenticationFilter(tokenService);
    }

    // AuthController is a @RestController picked up by the app's component scan, not registered here,
    // so it is not double-registered. The consuming app must provide a SecurityUserService bean (the
    // module's contract) for AuthController to wire.

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   AuthenticationEntryPoint entryPoint,
                                                   AccessDeniedHandler accessDeniedHandler,
                                                   SecurityModuleProperties props) throws Exception {
        List<String> publicPaths = new ArrayList<>(List.of(DEFAULT_PUBLIC_PATHS));
        publicPaths.addAll(props.getPublicPaths());

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(publicPaths.toArray(new String[0])).permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

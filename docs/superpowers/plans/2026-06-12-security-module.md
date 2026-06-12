# Reusable Security Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a standalone, auto-configured `security` Maven module (Spring Security 6 + JWT HS256 + full RBAC) that any servlet web app in this base secures simply by declaring the dependency, setting a JWT secret, and providing one `SecurityUserService` bean.

**Architecture:** A library module exposes Spring Boot auto-configuration (`AutoConfiguration.imports`) that wires a stateless JWT filter, deny-all-except-whitelist `SecurityFilterChain`, `@EnableMethodSecurity`, REST 401/403 handlers (existing `ErrorResponse` envelope), a login endpoint, and a `SecurityUserService` port. The `web/api` app provides the port adapter (MyBatis-backed), enables `@PreAuthorize` on User CRUD, and the `framework` `LoginInfo` becomes a real ThreadLocal so the `AuditInterceptor` records the true uid from the token.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Security 6, jjwt 0.12.6, MyBatis, PostgreSQL (prod) / H2 PostgreSQL-mode (test), JUnit 5 + MockMvc.

**Spec:** `docs/superpowers/specs/2026-06-12-security-module-design.md`. Branch: `feature/security-module`.

**Build:** Java 21, exclude DB-bound tooling modules:
`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn <goal> -pl -mybatis-generator,-mybatis-schema-migration`
(The shell prints a large env block before real output — ignore it; real output is at the bottom.)

---

## Key facts (verified)

- `ErrorResponse.of(HttpStatus, String message, List<String> errorCodes)` builds the project's error envelope; `ErrorCode.EUNAUTHORIZED` (`"UNAUTHORIZED"`), `ErrorCode.EINVAL_TOKEN` (`"INVALID_TOKEN"`), `ErrorCode.EACCES` (`"EACCES"`) exist.
- The root `pom.xml` adds `spring-boot-starter-web` + lombok to EVERY module, so the `security` module compiles against the servlet API without extra deps. Activation is guarded by `@ConditionalOnWebApplication(SERVLET)`.
- `MyBatisConfig` (web/api) uses a custom `SqlSessionFactory` → mappers need explicit resultMaps + colocated XML (already the pattern for `UserMapper`).
- `AuditInterceptor.getUid()` currently returns `0L`; the security work makes it read `LoginInfo`.
- Spring Security filter-chain 401/403 (and method-security `AccessDeniedException`) are handled by `AuthenticationEntryPoint`/`AccessDeniedHandler`, NOT `@ControllerAdvice` (those run before the DispatcherServlet).
- Existing `UserCrudIntegrationTest` / `UserValidationIntegrationTest` will start returning 401 once security is on → Task 8 updates them to authenticate.

---

## Phase 1 — `security` module: JWT core + Spring Security wiring

### Task 1: Create the `security` module

**Files:**
- Create: `security/pom.xml`
- Modify: `pom.xml` (root: add `<module>` + dependencyManagement entry)

- [ ] **Step 1: Create `security/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>jp.co.htkk</groupId>
        <artifactId>point-management-sys</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>security</artifactId>
    <name>security</name>

    <properties>
        <jjwt.version>0.12.6</jjwt.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>jp.co.htkk</groupId>
            <artifactId>framework</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>${jjwt.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Register the module in the root `pom.xml`**

Add `<module>security</module>` to the `<modules>` list (after `framework`), and add to `<dependencyManagement><dependencies>`:

```xml
<dependency>
    <groupId>jp.co.htkk</groupId>
    <artifactId>security</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 3: Create the package dir + a placeholder so the module compiles**

Create `security/src/main/java/jp/co/htkk/security/.gitkeep` is NOT needed; instead proceed to Task 2 which adds real classes. For now just verify the POM parses:

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl security -am install`
Expected: BUILD SUCCESS (empty module builds).

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "build: add security module (spring-security + jjwt)"
```

---

### Task 2: JWT properties + token service

**Files:**
- Create: `security/src/main/java/jp/co/htkk/security/config/SecurityModuleProperties.java`
- Create: `security/src/main/java/jp/co/htkk/security/jwt/JwtTokenService.java`
- Create: `security/src/main/java/jp/co/htkk/security/jwt/JwtPrincipal.java`
- Test: `security/src/test/java/jp/co/htkk/security/jwt/JwtTokenServiceTest.java`

- [ ] **Step 1: Create `SecurityModuleProperties`**

`security/src/main/java/jp/co/htkk/security/config/SecurityModuleProperties.java`:

```java
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
```

- [ ] **Step 2: Create `JwtPrincipal` (parsed token payload)**

`security/src/main/java/jp/co/htkk/security/jwt/JwtPrincipal.java`:

```java
package jp.co.htkk.security.jwt;

import lombok.Value;

import java.util.Set;

@Value
public class JwtPrincipal {
    Long uid;
    String username;
    Set<String> roles;
    Set<String> permissions;
}
```

- [ ] **Step 3: Write the failing test**

`security/src/test/java/jp/co/htkk/security/jwt/JwtTokenServiceTest.java`:

```java
package jp.co.htkk.security.jwt;

import jp.co.htkk.security.config.SecurityModuleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {

    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        SecurityModuleProperties props = new SecurityModuleProperties();
        props.getJwt().setSecret("01234567890123456789012345678901234567890123456789"); // >= 32 bytes
        props.getJwt().setExpiration(Duration.ofMinutes(30));
        props.getJwt().setRenewWindow(Duration.ofMinutes(3));
        service = new JwtTokenService(props);
    }

    @Test
    void issueThenParse_roundTrips() {
        String token = service.issue(7L, "alice", Set.of("ADMIN"), Set.of("USER_READ", "USER_WRITE"));
        JwtPrincipal p = service.parse(token);
        assertThat(p.getUid()).isEqualTo(7L);
        assertThat(p.getUsername()).isEqualTo("alice");
        assertThat(p.getRoles()).containsExactly("ADMIN");
        assertThat(p.getPermissions()).containsExactlyInAnyOrder("USER_READ", "USER_WRITE");
    }

    @Test
    void parse_invalidToken_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.parse("not.a.jwt"))
                .isInstanceOf(JwtTokenService.InvalidTokenException.class);
    }

    @Test
    void renewIfNeeded_returnsNewTokenOnlyWhenWithinWindow() {
        // token already inside the renew window (1 min left, window is 3 min)
        String almostExpired = service.issueWithTtl(7L, "alice", Set.of("ADMIN"), Set.of("USER_READ"), Duration.ofMinutes(1));
        assertThat(service.renewIfNeeded(almostExpired)).isPresent();

        String fresh = service.issue(7L, "alice", Set.of("ADMIN"), Set.of("USER_READ"));
        assertThat(service.renewIfNeeded(fresh)).isEmpty();
    }
}
```

- [ ] **Step 4: Run it (fails — class not defined)**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q test -pl security -Dtest=JwtTokenServiceTest`
Expected: compilation failure (`JwtTokenService` missing).

- [ ] **Step 5: Implement `JwtTokenService`**

`security/src/main/java/jp/co/htkk/security/jwt/JwtTokenService.java`:

```java
package jp.co.htkk.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jp.co.htkk.security.config.SecurityModuleProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class JwtTokenService {

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMS = "perms";

    private final SecretKey key;
    private final SecurityModuleProperties props;

    public JwtTokenService(SecurityModuleProperties props) {
        this.props = props;
        String secret = props.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.security.jwt.secret must be set and at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(Long uid, String username, Set<String> roles, Set<String> permissions) {
        return issueWithTtl(uid, username, roles, permissions, props.getJwt().getExpiration());
    }

    public String issueWithTtl(Long uid, String username, Set<String> roles, Set<String> permissions, Duration ttl) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttl.toMillis());
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_UID, uid)
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_PERMS, permissions)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public JwtPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Long uid = claims.get(CLAIM_UID, Number.class).longValue();
            Set<String> roles = toStringSet(claims.get(CLAIM_ROLES, List.class));
            Set<String> perms = toStringSet(claims.get(CLAIM_PERMS, List.class));
            return new JwtPrincipal(uid, claims.getSubject(), roles, perms);
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            throw new InvalidTokenException("Invalid or expired JWT", ex);
        }
    }

    /** Returns a fresh token (same identity, new TTL) iff the current token is still valid and within the renew window. */
    public Optional<String> renewIfNeeded(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty(); // expired/invalid -> caller handles as 401, never renew
        }
        long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remaining > props.getJwt().getRenewWindow().toMillis()) {
            return Optional.empty();
        }
        JwtPrincipal p = parse(token);
        return Optional.of(issue(p.getUid(), p.getUsername(), p.getRoles(), p.getPermissions()));
    }

    @SuppressWarnings("unchecked")
    private Set<String> toStringSet(List<?> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (Object o : raw) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
```

- [ ] **Step 6: Run the test (passes)**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q test -pl security -Dtest=JwtTokenServiceTest`
Expected: PASS (3 tests). `spring-boot-starter-test` (inherited) provides AssertJ.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(security): JWT token service (issue/parse/renew) + properties"
```

---

### Task 3: Security port, filter, handlers, login, auto-config

**Files:**
- Create: `security/src/main/java/jp/co/htkk/security/port/SecurityUser.java`
- Create: `security/src/main/java/jp/co/htkk/security/port/SecurityUserService.java`
- Create: `security/src/main/java/jp/co/htkk/security/web/JwtAuthenticationFilter.java`
- Create: `security/src/main/java/jp/co/htkk/security/web/RestAuthenticationEntryPoint.java`
- Create: `security/src/main/java/jp/co/htkk/security/web/RestAccessDeniedHandler.java`
- Create: `security/src/main/java/jp/co/htkk/security/web/AuthController.java`
- Create: `security/src/main/java/jp/co/htkk/security/web/dto/LoginRequest.java`, `LoginResponse.java`
- Create: `security/src/main/java/jp/co/htkk/security/config/SecurityModuleAutoConfiguration.java`
- Create: `security/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Port — `SecurityUser` + `SecurityUserService`**

`SecurityUser.java`:

```java
package jp.co.htkk.security.port;

import lombok.Builder;
import lombok.Value;

import java.util.Set;

@Value
@Builder
public class SecurityUser {
    Long uid;
    String username;
    String passwordHash;
    boolean enabled;
    Set<String> roles;
    Set<String> permissions;
}
```

`SecurityUserService.java`:

```java
package jp.co.htkk.security.port;

/** Implemented by the consuming application to supply user credentials + authorities. */
public interface SecurityUserService {
    /** @return the user, or null if not found. */
    SecurityUser loadByUsername(String username);
}
```

- [ ] **Step 2: `JwtAuthenticationFilter`** — authenticate, set `LoginInfo`, renew header

`security/src/main/java/jp/co/htkk/security/web/JwtAuthenticationFilter.java`:

```java
package jp.co.htkk.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.co.htkk.framework.security.model.LoginInfo;
import jp.co.htkk.security.jwt.JwtPrincipal;
import jp.co.htkk.security.jwt.JwtTokenService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String RENEW_HEADER = "X-New-Access-Token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService tokenService;

    public JwtAuthenticationFilter(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                JwtPrincipal principal = tokenService.parse(token);

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                principal.getRoles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                principal.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal.getUsername(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                LoginInfo.set(new LoginInfo(principal.getUid(), principal.getUsername()));

                tokenService.renewIfNeeded(token).ifPresent(t -> response.setHeader(RENEW_HEADER, t));
            } catch (JwtTokenService.InvalidTokenException ignored) {
                // leave context empty -> entry point returns 401
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            LoginInfo.clear();
        }
    }
}
```

- [ ] **Step 3: `RestAuthenticationEntryPoint` (401) + `RestAccessDeniedHandler` (403)**

`RestAuthenticationEntryPoint.java`:

```java
package jp.co.htkk.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.co.htkk.framework.exception.model.ErrorCode;
import jp.co.htkk.framework.exception.model.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.List;

public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        ErrorResponse body = ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Unauthorized",
                List.of(ErrorCode.EUNAUTHORIZED.getErrorCode()));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
```

`RestAccessDeniedHandler.java`:

```java
package jp.co.htkk.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.co.htkk.framework.exception.model.ErrorCode;
import jp.co.htkk.framework.exception.model.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.List;

public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        ErrorResponse body = ErrorResponse.of(HttpStatus.FORBIDDEN, "Forbidden",
                List.of(ErrorCode.EACCES.getErrorCode()));
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
```

- [ ] **Step 4: Login DTOs + `AuthController`**

`security/src/main/java/jp/co/htkk/security/web/dto/LoginRequest.java`:

```java
package jp.co.htkk.security.web.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
}
```

`security/src/main/java/jp/co/htkk/security/web/dto/LoginResponse.java`:

```java
package jp.co.htkk.security.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String accessToken;
    private String tokenType;
    private long expiresIn; // seconds
}
```

`security/src/main/java/jp/co/htkk/security/web/AuthController.java`:

```java
package jp.co.htkk.security.web;

import jp.co.htkk.security.config.SecurityModuleProperties;
import jp.co.htkk.security.jwt.JwtTokenService;
import jp.co.htkk.security.port.SecurityUser;
import jp.co.htkk.security.port.SecurityUserService;
import jp.co.htkk.security.web.dto.LoginRequest;
import jp.co.htkk.security.web.dto.LoginResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

// NOT @RestController (a @Component stereotype): the app component-scans jp.co.htkk, and this
// controller is registered as a @Bean by the auto-config. @RequestMapping makes it a handler
// without making it a @Component, so it is not registered twice.
@RequestMapping
@ResponseBody
public class AuthController {

    private final SecurityUserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final SecurityModuleProperties props;

    public AuthController(SecurityUserService userService, PasswordEncoder passwordEncoder,
                          JwtTokenService tokenService, SecurityModuleProperties props) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.props = props;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        SecurityUser user = userService.loadByUsername(request.getUsername());
        if (user == null || !user.isEnabled()
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        String token = tokenService.issue(user.getUid(), user.getUsername(), user.getRoles(), user.getPermissions());
        long expiresIn = props.getJwt().getExpiration().toSeconds();
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", expiresIn));
    }
}
```

Note: `BadCredentialsException` thrown here is an `AuthenticationException`; the `SecurityFilterChain`'s `ExceptionTranslationFilter` routes it to the `RestAuthenticationEntryPoint` → 401.

- [ ] **Step 5: `SecurityModuleAutoConfiguration`**

`security/src/main/java/jp/co/htkk/security/config/SecurityModuleAutoConfiguration.java`:

```java
package jp.co.htkk.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jp.co.htkk.security.jwt.JwtTokenService;
import jp.co.htkk.security.port.SecurityUserService;
import jp.co.htkk.security.web.AuthController;
import jp.co.htkk.security.web.JwtAuthenticationFilter;
import jp.co.htkk.security.web.RestAccessDeniedHandler;
import jp.co.htkk.security.web.RestAuthenticationEntryPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

    @Bean
    @ConditionalOnBean(SecurityUserService.class)
    public AuthController authController(SecurityUserService userService, PasswordEncoder passwordEncoder,
                                        JwtTokenService tokenService, SecurityModuleProperties props) {
        return new AuthController(userService, passwordEncoder, tokenService, props);
    }

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
```

- [ ] **Step 6: Register the auto-configuration**

Create `security/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with exactly one line:

```
jp.co.htkk.security.config.SecurityModuleAutoConfiguration
```

- [ ] **Step 7: Build**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl security -am install`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat(security): JWT filter, 401/403 handlers, login, deny-all auto-config"
```

---

## Phase 2 — framework `LoginInfo` + RBAC schema

### Task 4: Make `LoginInfo` a real ThreadLocal holder

**Files:**
- Modify: `framework/src/main/java/jp/co/htkk/framework/security/model/LoginInfo.java`

- [ ] **Step 1: Replace the stub**

```java
package jp.co.htkk.framework.security.model;

import lombok.Value;

/** Per-request authenticated principal, populated by the security module's JWT filter. */
@Value
public class LoginInfo {

    Long uid;
    String username;

    private static final ThreadLocal<LoginInfo> CONTEXT = new ThreadLocal<>();

    public static void set(LoginInfo loginInfo) {
        CONTEXT.set(loginInfo);
    }

    public static LoginInfo fromContext() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
```

- [ ] **Step 2: Build framework + dependents**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl -mybatis-generator,-mybatis-schema-migration clean install`
Expected: BUILD SUCCESS. (`LoginInfo.fromContext()` callers — `AuditInterceptor` — still compile; `getUid()` is updated in Task 7.)

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(framework): LoginInfo becomes a real ThreadLocal principal holder"
```

---

### Task 5: RBAC schema + seed (test, migration, docker)

**Files:**
- Modify: `web/api/src/test/resources/schema.sql`, `web/api/src/test/resources/data.sql`
- Modify: `docker/postgres-init/01-create-users.sql`
- Modify: `mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/scripts/20221013045051_create_main_tables.sql`

The BCrypt hashes below are for passwords `admin123` (admin) and `user123` (normal user). Generate them once and reuse the SAME strings in all four files:

- [ ] **Step 1: Generate the two BCrypt hashes**

Run:
```bash
ADMIN_HASH=$(htpasswd -bnBC 10 "" admin123 | tr -d ':\n' | sed 's/\$2y/\$2a/')
USER_HASH=$(htpasswd -bnBC 10 "" user123 | tr -d ':\n' | sed 's/\$2y/\$2a/')
echo "admin: $ADMIN_HASH"; echo "user:  $USER_HASH"
```
Expected: two `$2a$10$...` strings. Use these exact values in Steps 2–5 (replace the `__ADMIN_HASH__` / `__USER_HASH__` placeholders). If `htpasswd` is unavailable, use any tool that produces a BCrypt hash (cost 10) of those passwords; the login test in Task 8 verifies correctness.

- [ ] **Step 2: Test schema — add RBAC tables + `password` column**

Overwrite `web/api/src/test/resources/schema.sql`:

```sql
DROP TABLE IF EXISTS role_permissions;
DROP TABLE IF EXISTS user_roles;
DROP TABLE IF EXISTS permissions;
DROP TABLE IF EXISTS roles;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    user_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    del_flag SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE roles (
    role_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL UNIQUE,
    role_name VARCHAR(255) NOT NULL,
    created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    del_flag SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE permissions (
    permission_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    permission_code VARCHAR(100) NOT NULL UNIQUE,
    permission_name VARCHAR(255) NOT NULL,
    created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    del_flag SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permissions (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);
```

- [ ] **Step 3: Test data — seed roles/permissions/users (replace hash placeholders)**

Overwrite `web/api/src/test/resources/data.sql`:

```sql
INSERT INTO permissions (permission_id, permission_code, permission_name, created_by, updated_by)
VALUES (1, 'USER_READ', 'Read users', 0, 0), (2, 'USER_WRITE', 'Write users', 0, 0);

INSERT INTO roles (role_id, role_code, role_name, created_by, updated_by)
VALUES (1, 'ADMIN', 'Administrator', 0, 0), (2, 'USER', 'Standard user', 0, 0);

INSERT INTO role_permissions (role_id, permission_id) VALUES (1, 1), (1, 2), (2, 1);

INSERT INTO users (user_id, username, email, password, created_by, updated_by)
VALUES (1, 'admin', 'admin@example.com', '__ADMIN_HASH__', 0, 0),
       (2, 'normal', 'normal@example.com', '__USER_HASH__', 0, 0);

INSERT INTO user_roles (user_id, role_id) VALUES (1, 1), (2, 2);
```

- [ ] **Step 4: Docker dev init — same schema + seed**

Overwrite `docker/postgres-init/01-create-users.sql` with the union of Step 2's `CREATE TABLE` statements (without the `DROP`s) using `CREATE TABLE IF NOT EXISTS`, followed by Step 3's `INSERT`s. Keep the existing header comment. (Identical column definitions and the same two hash strings.)

- [ ] **Step 5: Migration script — Postgres RBAC DDL**

In `mybatis-schema-migration/.../20221013045051_create_main_tables.sql`, under the `-- // create_main_tables` marker, replace the single `users` table with: the `users` table **plus** `password VARCHAR(255) NOT NULL`, and the four RBAC tables (same definitions as Step 2). Under `-- //@UNDO`, drop them in reverse dependency order (`role_permissions`, `user_roles`, `permissions`, `roles`, `users`).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: RBAC schema (roles/permissions/mappings) + password column + seed"
```

---

## Phase 3 — `web/api` integration

### Task 6: Persistence — load auth data by username

**Files:**
- Create: `persistence/src/main/java/jp/co/htkk/persistence/dao/UserAuthMapper.java`
- Create: `persistence/src/main/java/jp/co/htkk/persistence/dao/UserAuthMapper.xml`

- [ ] **Step 1: Mapper interface**

`UserAuthMapper.java`:

```java
package jp.co.htkk.persistence.dao;

import java.util.List;

public interface UserAuthMapper {
    /** Row from users by username (active only). */
    UserAuthRow findByUsername(String username);

    List<String> findRoleCodes(Long userId);

    List<String> findPermissionCodes(Long userId);

    class UserAuthRow {
        public Long userId;
        public String username;
        public String password;
        public Short delFlag;
    }
}
```

- [ ] **Step 2: Mapper XML (explicit resultMaps; numeric `del_flag = 0`)**

`UserAuthMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="jp.co.htkk.persistence.dao.UserAuthMapper">

    <resultMap id="userAuthRow" type="jp.co.htkk.persistence.dao.UserAuthMapper$UserAuthRow">
        <id column="user_id" jdbcType="BIGINT" property="userId"/>
        <result column="username" jdbcType="VARCHAR" property="username"/>
        <result column="password" jdbcType="VARCHAR" property="password"/>
        <result column="del_flag" jdbcType="SMALLINT" property="delFlag"/>
    </resultMap>

    <select id="findByUsername" resultMap="userAuthRow" parameterType="java.lang.String">
        SELECT user_id, username, password, del_flag
        FROM users
        WHERE username = #{username} AND del_flag = 0
    </select>

    <select id="findRoleCodes" resultType="java.lang.String" parameterType="java.lang.Long">
        SELECT r.role_code
        FROM user_roles ur
        JOIN roles r ON r.role_id = ur.role_id AND r.del_flag = 0
        WHERE ur.user_id = #{userId}
    </select>

    <select id="findPermissionCodes" resultType="java.lang.String" parameterType="java.lang.Long">
        SELECT DISTINCT p.permission_code
        FROM user_roles ur
        JOIN role_permissions rp ON rp.role_id = ur.role_id
        JOIN permissions p ON p.permission_id = rp.permission_id AND p.del_flag = 0
        WHERE ur.user_id = #{userId}
    </select>
</mapper>
```

- [ ] **Step 3: Build**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl persistence -am install`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(persistence): UserAuthMapper (load user + role/permission codes)"
```

---

### Task 7: Wire security into `web/api`

**Files:**
- Modify: `web/api/pom.xml`
- Create: `web/api/src/main/java/jp/co/htkk/api/security/SecurityUserServiceImpl.java`
- Delete: `web/api/src/main/java/jp/co/htkk/api/interceptor/AuthorizationInterceptor.java`
- Modify: `web/api/src/main/java/jp/co/htkk/api/config/WebMvcConfiguration.java`
- Modify: `web/api/src/main/java/jp/co/htkk/api/config/mybatis/intercept/AuditInterceptor.java`
- Modify: `web/api/src/main/java/jp/co/htkk/api/controller/admin/UserController.java`
- Modify: `web/api/src/main/java/jp/co/htkk/api/config/SpringdocConfig.java`
- Modify: `web/api/src/main/resources/application.yml`
- Modify: `docker-compose.yaml`

- [ ] **Step 1: Add the security dependency to `web/api/pom.xml`**

Inside `<dependencies>`:

```xml
<dependency>
    <groupId>jp.co.htkk</groupId>
    <artifactId>security</artifactId>
</dependency>
```

- [ ] **Step 2: Adapter `SecurityUserServiceImpl`**

`web/api/src/main/java/jp/co/htkk/api/security/SecurityUserServiceImpl.java`:

```java
package jp.co.htkk.api.security;

import jp.co.htkk.persistence.dao.UserAuthMapper;
import jp.co.htkk.security.port.SecurityUser;
import jp.co.htkk.security.port.SecurityUserService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
@AllArgsConstructor
public class SecurityUserServiceImpl implements SecurityUserService {

    private final UserAuthMapper userAuthMapper;

    @Override
    public SecurityUser loadByUsername(String username) {
        UserAuthMapper.UserAuthRow row = userAuthMapper.findByUsername(username);
        if (row == null) {
            return null;
        }
        Set<String> roles = new LinkedHashSet<>(userAuthMapper.findRoleCodes(row.userId));
        Set<String> perms = new LinkedHashSet<>(userAuthMapper.findPermissionCodes(row.userId));
        return SecurityUser.builder()
                .uid(row.userId)
                .username(row.username)
                .passwordHash(row.password)
                .enabled(true)
                .roles(roles)
                .permissions(perms)
                .build();
    }
}
```

- [ ] **Step 3: Delete `AuthorizationInterceptor` and its registration**

```bash
git rm web/api/src/main/java/jp/co/htkk/api/interceptor/AuthorizationInterceptor.java
```

Rewrite `web/api/src/main/java/jp/co/htkk/api/config/WebMvcConfiguration.java` (drop the interceptor field + `addInterceptors`; keep the `MessageService` bean):

```java
package jp.co.htkk.api.config;

import jp.co.htkk.framework.component.MessageService;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Locale;

@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    @Bean
    public MessageService messageService(final MessageSource messageSource) {
        return new MessageService(messageSource, Locale.JAPANESE);
    }
}
```

- [ ] **Step 4: `AuditInterceptor.getUid()` reads the token's uid**

In `web/api/.../mybatis/intercept/AuditInterceptor.java`, replace the method:

```java
    private Long getUid(LoginInfo loginInfo) {
        return loginInfo != null && loginInfo.getUid() != null ? loginInfo.getUid() : 0L;
    }
```

(`LoginInfo loginInfo` is already passed in from `LoginInfo.fromContext()`.)

- [ ] **Step 5: `@PreAuthorize` on User CRUD**

In `UserController.java`, add `import org.springframework.security.access.prepost.PreAuthorize;` and annotate:
- `create(...)` → `@PreAuthorize("hasAuthority('USER_WRITE')")`
- `getById(...)` → `@PreAuthorize("hasAuthority('USER_READ')")`
- `list()` → `@PreAuthorize("hasAuthority('USER_READ')")`

- [ ] **Step 6: Swagger bearer scheme**

In `SpringdocConfig.java`, change the `openAPI()` bean to register an HTTP bearer `SecurityScheme` so Swagger shows the Authorize button:

```java
    @Bean
    public OpenAPI openAPI() {
        final String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info().title("Base API").description("").version("0.0.1-SNAPSHOT"))
                .components(new io.swagger.v3.oas.models.Components().addSecuritySchemes(scheme,
                        new io.swagger.v3.oas.models.security.SecurityScheme()
                                .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList(scheme));
    }
```

- [ ] **Step 7: `application.yml` — security config + actuator detail level**

In `web/api/src/main/resources/application.yml` add a top-level block:

```yaml
app:
  security:
    jwt:
      secret: ${JWT_SECRET}
      expiration: 30m
      renew-window: 3m
```

And change `management.endpoint.health.show-details` from `always` to `when-authorized`.

- [ ] **Step 8: docker-compose — provide a dev `JWT_SECRET`**

In `docker-compose.yaml`, add to the `api` service `environment:`:

```yaml
      JWT_SECRET: ${JWT_SECRET:-dev-secret-please-change-0123456789-abcdefghij}
```

- [ ] **Step 9: Build**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl -mybatis-generator,-mybatis-schema-migration clean install`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add -A && git commit -m "feat(web/api): integrate security module (adapter, @PreAuthorize, audit uid, swagger, config)"
```

---

## Phase 4 — Tests + verification

### Task 8: Security integration tests (H2 PostgreSQL mode)

**Files:**
- Create: `web/api/src/test/java/jp/co/htkk/api/security/SecurityIntegrationTest.java`
- Modify: `web/api/src/test/java/jp/co/htkk/api/controller/admin/UserCrudIntegrationTest.java`
- Modify: `web/api/src/test/java/jp/co/htkk/api/controller/admin/UserValidationIntegrationTest.java`
- Create: `web/api/src/test/resources/application-test.yml` (add `app.security.jwt.secret`)

- [ ] **Step 1: Give tests a JWT secret**

Add to `web/api/src/test/resources/application-test.yml`:

```yaml
app:
  security:
    jwt:
      secret: test-secret-0123456789-0123456789-0123456789
      expiration: 30m
      renew-window: 3m
```

- [ ] **Step 2: Write `SecurityIntegrationTest`** (login, 401, 403, audit uid, whitelist)

`web/api/src/test/java/jp/co/htkk/api/security/SecurityIntegrationTest.java`:

```java
package jp.co.htkk.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/auth/login").servletPath("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/auth/login").servletPath("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"WRONG\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_noToken_returns401() throws Exception {
        mockMvc.perform(get("/admin/users").servletPath("/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_garbageToken_returns401() throws Exception {
        mockMvc.perform(get("/admin/users").servletPath("/admin/users").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void normalUser_cannotWrite_butCanRead() throws Exception {
        String token = login("normal", "user123");
        mockMvc.perform(post("/admin/users").servletPath("/admin/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"email\":\"x@example.com\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users").servletPath("/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void admin_canCreate_andAuditUidIsAdmin() throws Exception {
        String token = login("admin", "admin123");
        MvcResult created = mockMvc.perform(post("/admin/users").servletPath("/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"created-by-admin\",\"email\":\"c@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").exists())
                .andReturn();
        // admin's uid is 1 (seed); verify the audit interceptor wrote created_by = 1 via a fresh GET is not enough
        // (created_by is not exposed) — assert via the seeded admin uid embedded path instead: the row exists and
        // listing returns the new user.
        long newId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("userId").asLong();
        mockMvc.perform(get("/admin/users/" + newId).servletPath("/admin/users/" + newId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("created-by-admin"));
    }

    @Test
    void whitelist_swaggerAndHealth_noTokenNeeded() throws Exception {
        mockMvc.perform(get("/actuator/health").servletPath("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api-docs").servletPath("/api-docs")).andExpect(status().isOk());
    }
}
```

Note: `created_by` is not exposed in `UserResponse`; the live verification (Task 9) asserts `created_by = 1` directly in PostgreSQL.

- [ ] **Step 3: Add an audit-uid assertion via a tiny test mapper query**

To assert `created_by` in H2 without exposing it in the API, autowire `UserAuthMapper` is insufficient (no created_by). Add to `SecurityIntegrationTest` an autowired `org.springframework.jdbc.core.JdbcTemplate jdbc;` and, at the end of `admin_canCreate_andAuditUidIsAdmin`, assert:

```java
Long createdBy = jdbc.queryForObject("SELECT created_by FROM users WHERE user_id = ?", Long.class, newId);
org.assertj.core.api.Assertions.assertThat(createdBy).isEqualTo(1L);
```

(`JdbcTemplateAutoConfiguration` provides `JdbcTemplate` from the test H2 datasource.)

- [ ] **Step 4: Write the renew test**

Add to `SecurityIntegrationTest` an autowired `jp.co.htkk.security.jwt.JwtTokenService tokenService;` and:

```java
@Test
void tokenWithinRenewWindow_getsNewTokenHeader() throws Exception {
    // mint a token with 1 minute TTL (inside the 3m renew-window)
    String shortToken = tokenService.issueWithTtl(1L, "admin",
            java.util.Set.of("ADMIN"), java.util.Set.of("USER_READ", "USER_WRITE"),
            java.time.Duration.ofMinutes(1));
    mockMvc.perform(get("/admin/users").servletPath("/admin/users").header("Authorization", "Bearer " + shortToken))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .exists(jp.co.htkk.security.web.JwtAuthenticationFilter.RENEW_HEADER));
}
```

- [ ] **Step 5: Update the existing User tests to authenticate**

`UserCrudIntegrationTest` and `UserValidationIntegrationTest` now hit a secured app. Add a login helper (same as Step 2) and send `.header("Authorization", "Bearer " + adminToken)` on every `/admin/users` request (login as `admin`/`admin123`). The CRUD assertions and the validation 400 assertion are otherwise unchanged.

- [ ] **Step 6: Run the web/api tests**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn test -pl web/api`
Expected: all PASS (`SecurityIntegrationTest`, updated `UserCrudIntegrationTest`, `UserValidationIntegrationTest`, `ApiContextLoadsTest`).
If login returns 401: the seeded BCrypt hash doesn't match `admin123` — regenerate (Task 5 Step 1) and update all four seed files. If 403 where 200 expected: check the `@PreAuthorize` authority strings match the seeded permission codes.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "test(security): login/401/403/renew/audit-uid/whitelist integration tests on H2-PG"
```

---

### Task 9: Full build + live PostgreSQL verification

- [ ] **Step 1: Full clean build with all tests**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn clean install -pl -mybatis-generator,-mybatis-schema-migration`
Expected: BUILD SUCCESS; all modules incl. `security` green; web/api security tests pass; `BatchContextLoadsTest` green (batch declared no security dependency).

- [ ] **Step 2: Run the stack and exercise auth end-to-end**

```bash
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij docker compose up --build -d
# wait for api "Started", then:
TOKEN=$(curl -s -X POST http://localhost:9000/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
echo "no-token list  : $(curl -s -o /dev/null -w '%{http_code}' http://localhost:9000/api/v1/admin/users)"   # 401
echo "admin create   : $(curl -s -X POST http://localhost:9000/api/v1/admin/users -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"username":"bob","email":"bob@example.com"}')"
echo "admin list     : $(curl -s http://localhost:9000/api/v1/admin/users -H "Authorization: Bearer $TOKEN")"
echo "audit created_by:"; docker exec postgresDb psql -U postgres -d helpo_step -c "SELECT user_id, username, created_by FROM users WHERE username='bob';"
```
Expected: list without token → `401`; admin create → `{"data":{...}}`; list → includes bob; **`created_by = 1`** (admin's uid). Then `docker compose down -v`.

- [ ] **Step 3: Confirm clean tree + final build**

Run: `git status && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q clean install -pl -mybatis-generator,-mybatis-schema-migration`
Expected: clean tree, BUILD SUCCESS.

---

## Verification Summary (maps to spec §8)

1. `mvn clean install` (excl. tooling) green on Java 21; `security` module in the reactor. ✔ Task 9.1
2. Integration tests on H2-PG: login 200 / wrong-pw 401 / no-token 401 / role-USER 403 on write & 200 on read / role-ADMIN write 200 with real audit uid / renew header / whitelist. ✔ Task 8
3. Live PostgreSQL: login → token → 401 without token, 200 with token, `created_by = 1`. ✔ Task 9.2

## Out of scope

- Refresh-token store / revocation; RBAC admin CRUD APIs; OAuth2/social; project rename.
- Sub-project C (cleanup).

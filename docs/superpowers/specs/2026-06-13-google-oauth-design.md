# Google OAuth Login — Design Spec

**Date:** 2026-06-13
**Topic:** Thêm Google OAuth 2.0 Authorization Code Flow vào hệ thống auth hiện có, tổ chức theo aggregator pattern để mở rộng provider tương lai.
**Status:** Approved (brainstorming complete, awaiting implementation plan)

## Goal

Hôm nay user chỉ login được qua `/auth/login` (username + password BCrypt → JWT). Thêm flow "Sign in with Google":

- FE redirect user đến Google authorize, nhận `authorization code`.
- FE POST code lên BE; BE exchange với Google qua `client_secret`, verify `id_token`, upsert user nội bộ, cấp JWT.
- Auto-link theo email nếu user đã tồn tại; auto-signup (gán role USER) nếu chưa.
- Module security tách thành aggregator `security/{core,google}` để các app khác dùng được Google login mà không phải lôi cả password login.

## Decisions

1. **OAuth flow**: Authorization Code Flow (Pattern A), BE confidential client giữ `client_secret`. KHÔNG PKCE ở V1 (defer; mobile sau dùng endpoint `/auth/google/id-token` riêng — defer).
2. **Account linking**: Auto-link theo email khi `email_verified=true`. User cũ có password vẫn dùng được cả 2 cách login sau khi link.
3. **First-time Google user**: Auto-signup, gán role mặc định (`USER` qua `app.security.oauth.google.default-role-code`).
4. **Schema**: Thêm cột `google_sub VARCHAR(255)` vào `users` + unique partial index. `username = email`, `password = ''` cho user OAuth-only.
5. **Module layout**: Refactor `security/` → aggregator chứa `security/core/` (artifactId `security-core`, move logic hiện tại) + `security/google/` (artifactId `security-google`, mới).
6. **Port contract**: Module `security-google` định nghĩa interface `GoogleUserSyncService` — app cung cấp implementation upsert user logic. Module KHÔNG biết về MyBatis hay schema cụ thể.

## Architecture

### Module layout sau refactor

```
security/                                    (packaging=pom, aggregator)
├── pom.xml                                  (shared deps: jjwt, spring-security-core)
├── core/                                    (move từ security/ hiện tại)
│   ├── pom.xml                              (artifactId: security-core)
│   └── src/main/java/jp/co/htkk/security/
│       ├── jwt/                             (JwtTokenService, JwtPrincipal)
│       ├── config/                          (SecurityModuleAutoConfiguration, Properties)
│       ├── web/                             (AuthController, JwtAuthenticationFilter, ...)
│       ├── web/dto/                         (LoginRequest, LoginResponse)
│       └── port/                            (SecurityUserService, SecurityUser)
└── google/                                  (MỚI)
    ├── pom.xml                              (artifactId: security-google, dep: security-core +
    │                                         com.google.api-client:google-api-client)
    └── src/main/java/jp/co/htkk/security/google/
        ├── config/
        │   ├── GoogleOAuthAutoConfiguration.java
        │   └── GoogleOAuthProperties.java
        ├── web/
        │   ├── GoogleAuthController.java    (POST /auth/google/callback)
        │   └── dto/
        │       ├── GoogleCallbackRequest.java
        │       └── GoogleUserInfo.java
        ├── service/
        │   ├── GoogleAuthService.java       (orchestrate exchange + sync + issue JWT)
        │   ├── GoogleTokenClient.java       (RestClient → oauth2.googleapis.com/token)
        │   └── GoogleIdTokenVerifier.java   (verify chữ ký id_token qua Google JWKS)
        └── port/
            └── GoogleUserSyncService.java   (interface, app implement)
```

App `application/api` dependency:
- Đổi `<artifactId>security</artifactId>` → `<artifactId>security-core</artifactId>`.
- Thêm `<artifactId>security-google</artifactId>`.

Sau này thêm provider mới: tạo `security/<provider>/` cùng pattern, app khai dependency thêm.

### Runtime flow

```
1. Frontend                                                      
   └─→ window.location = https://accounts.google.com/o/oauth2/v2/auth?
       client_id=...&redirect_uri=https://app/oauth/callback
       &response_type=code&scope=openid email profile&state=<random>
   (state lưu sessionStorage)
                                                                 
2. Google: user login + consent                                  
                                                                 
3. Browser redirect → https://app/oauth/callback?code=X&state=Y
   Frontend so sánh state với sessionStorage; OK:
                                                                 
4. POST /api/v1/auth/google/callback                             
   { "code": "X", "redirectUri": "https://app/oauth/callback" }
                              │                                  
5.                            ↓ Backend                          
   - Validate redirectUri trong allowed-redirect-uris whitelist
   - POST oauth2.googleapis.com/token với client_id, client_secret, code, redirect_uri, grant_type
   - Nhận { access_token, id_token (JWT), expires_in, ... }
   - Verify chữ ký id_token qua google-api-client lib (Google JWKS, RS256)
       + check iss=https://accounts.google.com, aud=clientId, exp valid
   - Decode claims: sub, email, email_verified, name
   - Reject nếu email_verified=false
                                                                 
6. Backend: GoogleUserSyncService.syncFromGoogle(info)           
   - Tìm user theo google_sub → có: dùng row đó
   - Không có → tìm theo email                                   
       → có: UPDATE google_sub
       → không có: INSERT (username=email, password='', google_sub=sub) + INSERT user_roles
   - Load roles + permissions → return SecurityUser
                                                                 
7. Backend: tokenService.issue(uid, username, roles, permissions)
                                                                 
8. Response: { "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 1800 }
```

### Public contract (cho app khác tái sử dụng)

App tiêu thụ cần:
1. Khai dependency `security-google` (transitive pull `security-core`).
2. Set env vars `app.security.oauth.google.client-id`, `client-secret`, `allowed-redirect-uris`.
3. Cung cấp bean `GoogleUserSyncService`:
   ```java
   public interface GoogleUserSyncService {
       /**
        * Find existing user, link if needed, create if not.
        * @return SecurityUser ready for JwtTokenService.issue(...)
        * @throws BadCredentialsException if user disabled or sync fails
        */
       SecurityUser syncFromGoogle(GoogleUserInfo info);
   }
   ```
4. (Optional) Override `app.security.oauth.google.default-role-code` nếu role mặc định khác `USER`.

## DB schema

### ALTER

```sql
ALTER TABLE users ADD COLUMN google_sub VARCHAR(255);
CREATE UNIQUE INDEX users_google_sub_uk ON users (google_sub) WHERE google_sub IS NOT NULL;
```

### Files cần sửa

1. `docker/postgres/init/01-create-users.sql` — thêm cột + index vào CREATE TABLE block (dev seed).
2. `mybatis-schema-migration/scripts/<timestamp>_add_google_sub_to_users.sql` — migration cho staging/prod.
3. Entity generated: chạy lại `mybatis-generator:generate` (cần DB sống) để regenerate `entity.generator.User`, `User.Column`, `UserCriteria`.

### Sample data sau

User Google mới:
```
user_id | username           | email              | password | google_sub             
--------+--------------------+--------------------+----------+------------------------
101     | nguyen@gmail.com   | nguyen@gmail.com   | ''       | 110169484474386276334  
```

User cũ admin sau khi link:
```
user_id | username | email             | password (BCrypt) | google_sub             
--------+----------+-------------------+-------------------+------------------------
1       | admin    | admin@example.com | $2a$10$eHdnG7uG.. | 110169484474386276335  
```

### Custom mapper

`persistence/dao/custom/CustomUserOAuthMapper.java` (mới):
```java
public interface CustomUserOAuthMapper {
    User findByGoogleSub(@Param("googleSub") String googleSub);
    User findByEmail(@Param("email") String email);
    int linkGoogleSub(@Param("userId") Long userId, @Param("googleSub") String googleSub);
}
```

INSERT user mới dùng generated `UserMapper.insertSelective(User)`. INSERT user_roles dùng generated `UserRoleMapper.insert(UserRole)` — tra `role_id` theo `role_code` qua query `roles.role_code = ?`.

## Internals của `security/google`

### `GoogleOAuthProperties`

```java
@Data
@ConfigurationProperties(prefix = "app.security.oauth.google")
public class GoogleOAuthProperties {
    private boolean enabled = true;
    private String clientId;
    private String clientSecret;
    private List<String> allowedRedirectUris = new ArrayList<>();
    private String defaultRoleCode = "USER";
    private Duration httpTimeout = Duration.ofSeconds(5);
}
```

### `GoogleOAuthAutoConfiguration`

```java
@AutoConfiguration(after = SecurityModuleAutoConfiguration.class)
@ConditionalOnProperty(prefix="app.security.oauth.google", name="enabled", havingValue="true", matchIfMissing=true)
@EnableConfigurationProperties(GoogleOAuthProperties.class)
public class GoogleOAuthAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    GoogleTokenClient googleTokenClient(GoogleOAuthProperties props) {
        return new GoogleTokenClient(props);
    }

    @Bean @ConditionalOnMissingBean
    GoogleIdTokenVerifier googleIdTokenVerifier(GoogleOAuthProperties props) {
        return new GoogleIdTokenVerifier(props);
    }

    @Bean @ConditionalOnMissingBean
    GoogleAuthService googleAuthService(GoogleTokenClient client,
                                         GoogleIdTokenVerifier verifier,
                                         GoogleUserSyncService userSyncService,
                                         JwtTokenService tokenService,
                                         GoogleOAuthProperties googleProps,
                                         SecurityModuleProperties securityProps) {
        return new GoogleAuthService(client, verifier, userSyncService, tokenService, googleProps, securityProps);
    }

    // GoogleAuthController là @RestController, app component-scan pick up
}
```

Đăng ký auto-config trong `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

### `GoogleAuthController`

```java
@RestController
@RequestMapping("/auth/google")
public class GoogleAuthController {
    private final GoogleAuthService service;
    
    public GoogleAuthController(GoogleAuthService service) {
        this.service = service;
    }
    
    @PostMapping("/callback")
    public ResponseEntity<LoginResponse> callback(@Valid @RequestBody GoogleCallbackRequest req) {
        return ResponseEntity.ok(service.handleCallback(req.getCode(), req.getRedirectUri()));
    }
}
```

### `GoogleCallbackRequest`

```java
@Data
public class GoogleCallbackRequest {
    @NotBlank private String code;
    @NotBlank private String redirectUri;
}
```

### `GoogleAuthService.handleCallback()`

```java
public LoginResponse handleCallback(String code, String redirectUri) {
    // 1. Whitelist check
    if (!googleProps.getAllowedRedirectUris().contains(redirectUri)) {
        throw new BadCredentialsException("Invalid redirect URI");
    }
    
    // 2. Exchange code → tokens
    GoogleTokenResponse tokens = tokenClient.exchange(code, redirectUri);
    
    // 3. Verify id_token
    GoogleUserInfo info = idTokenVerifier.verify(tokens.getIdToken());
    
    // 4. Reject nếu email chưa verify
    if (!info.isEmailVerified()) {
        throw new BadCredentialsException("Google email not verified");
    }
    if (info.getEmail() == null || info.getEmail().isBlank()) {
        throw new BadCredentialsException("Google email scope required");
    }
    
    // 5. Upsert user qua port
    SecurityUser user = userSyncService.syncFromGoogle(info);
    if (!user.isEnabled()) {
        throw new BadCredentialsException("Account disabled");
    }
    
    // 6. Issue JWT
    String token = tokenService.issue(user.getUid(), user.getUsername(),
                                       user.getRoles(), user.getPermissions());
    long expiresIn = securityProps.getJwt().getExpiration().toSeconds();
    return new LoginResponse(token, "Bearer", expiresIn);
}
```

### `GoogleTokenClient.exchange()`

```java
public GoogleTokenResponse exchange(String code, String redirectUri) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("code", code);
    form.add("client_id", props.getClientId());
    form.add("client_secret", props.getClientSecret());
    form.add("redirect_uri", redirectUri);
    form.add("grant_type", "authorization_code");
    
    try {
        return restClient.post().uri("/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(GoogleTokenResponse.class);
    } catch (HttpClientErrorException e) {
        // Google trả 4xx khi code invalid / expired / reused
        log.warn("Google token exchange failed: {}", e.getResponseBodyAsString());
        throw new BadCredentialsException("Google authorization failed");
    } catch (Exception e) {
        log.error("Google token endpoint error", e);
        throw new GoogleAuthException("Google service unavailable", e);
    }
}
```

### `GoogleIdTokenVerifier.verify()`

Dùng lib `com.google.api-client:google-api-client` (đã có cache JWKS, retry, key rotation):

```java
public GoogleUserInfo verify(String idTokenString) {
    com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier verifier =
        new com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier.Builder(
            new NetHttpTransport(), GsonFactory.getDefaultInstance())
        .setAudience(Collections.singletonList(props.getClientId()))
        .setIssuers(List.of("accounts.google.com", "https://accounts.google.com"))
        .build();
    
    try {
        GoogleIdToken token = verifier.verify(idTokenString);
        if (token == null) throw new BadCredentialsException("Invalid Google id_token");
        
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
```

### `GoogleUserInfo`

```java
@Value @Builder
public class GoogleUserInfo {
    String sub;             // bất biến, primary identifier
    String email;
    boolean emailVerified;
    String name;
    String picture;
}
```

### Implementation `GoogleUserSyncServiceImpl` trong `application/api`

```java
@Service
public class GoogleUserSyncServiceImpl implements GoogleUserSyncService {
    private final CustomUserOAuthMapper oauthMapper;
    private final UserMapper userMapper;             // generated
    private final UserRoleMapper userRoleMapper;     // generated
    private final RoleMapper roleMapper;             // generated
    private final UserAuthMapper userAuthMapper;     // existing (loads roles+permissions)
    private final GoogleOAuthProperties props;

    @Transactional
    public SecurityUser syncFromGoogle(GoogleUserInfo info) {
        User user = oauthMapper.findByGoogleSub(info.getSub());
        if (user == null) {
            user = oauthMapper.findByEmail(info.getEmail());
            if (user != null) {
                oauthMapper.linkGoogleSub(user.getUserId(), info.getSub());
            } else {
                user = createOauthUser(info);
            }
        }
        SecurityUser result = userAuthMapper.findSecurityUserById(user.getUserId());
        if (result == null) {
            throw new BadCredentialsException("User sync failed: no roles");
        }
        return result;
    }

    private User createOauthUser(GoogleUserInfo info) {
        try {
            User u = new User();
            u.setUsername(info.getEmail());
            u.setEmail(info.getEmail());
            u.setPassword("");
            u.setGoogleSub(info.getSub());
            u.setDelFlag(EDeleteFlag.NOT_DELETED.getCode());
            userMapper.insertSelective(u);

            Role role = findRoleByCode(props.getDefaultRoleCode());
            UserRole ur = new UserRole();
            ur.setUserId(u.getUserId());
            ur.setRoleId(role.getRoleId());
            userRoleMapper.insert(ur);
            return u;
        } catch (DuplicateKeyException e) {
            // Race condition: another concurrent request inserted first
            log.warn("Concurrent insert detected for google_sub={}, re-fetching", info.getSub());
            return oauthMapper.findByGoogleSub(info.getSub());
        }
    }
}
```

## Config

`application.yml`:
```yaml
app:
  security:
    jwt:
      secret: ${JWT_SECRET}
      # ... (giữ nguyên)
    public-paths:
      - /auth/google/**
    oauth:
      google:
        enabled: ${GOOGLE_OAUTH_ENABLED:true}
        client-id: ${GOOGLE_OAUTH_CLIENT_ID}
        client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET}
        allowed-redirect-uris:
          - ${GOOGLE_OAUTH_REDIRECT_URI}
        default-role-code: USER
        http-timeout: 5s
```

`.env.example` thêm:
```
# --- Google OAuth (REQUIRED if GOOGLE_OAUTH_ENABLED=true) ---
GOOGLE_OAUTH_CLIENT_ID=
GOOGLE_OAUTH_CLIENT_SECRET=
GOOGLE_OAUTH_REDIRECT_URI=https://app.example.com/oauth/callback
GOOGLE_OAUTH_ENABLED=true
```

Staging/prod compose: dùng `${VAR:?}` cho `CLIENT_ID`, `CLIENT_SECRET`, `REDIRECT_URI` để fail-fast.

## Security considerations

1. **redirect_uri whitelist** — FE gửi `redirectUri`, BE check `allowed-redirect-uris`. Chống open-redirect attack.
2. **id_token verification BẮT BUỘC** — `iss`, `aud`, `exp`, signature (RS256 qua JWKS), `email_verified`. Lib `google-api-client` xử lý.
3. **`code` single-use** — Google enforce. FE không retry POST với cùng code.
4. **HTTPS bắt buộc trên prod** — Google reject non-HTTPS redirect_uri (trừ `http://localhost` cho dev).
5. **`client_secret` chỉ ở BE** — đảm bảo qua Pattern A (FE chỉ thấy code).
6. **Username collision** — auto-link logic tìm theo email TRƯỚC khi insert. UNIQUE constraint trên `email` cũng nên có (out of scope, ghi Notes).
7. **Audit `created_by` cho user OAuth** — fallback `0L` vì lúc tạo user chưa có authenticated principal. Document, không fix ở spec này.
8. **Password rỗng KHÔNG cho login** — `BCryptPasswordEncoder.matches("anything", "")` trả false (Spring Security source). Thêm early-check trong `AuthController` để sạch log warning + generic message tránh enumeration:
   ```java
   if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
       throw new BadCredentialsException("Invalid username or password");
   }
   ```

## Error handling

| Tình huống | Exception | HTTP | ErrorCode |
|---|---|---|---|
| `code`/`redirectUri` thiếu | `MethodArgumentNotValidException` | 400 | `EINVAL` |
| `redirectUri` không whitelist | `BadCredentialsException` | 401 | `EUNAUTHORIZED` |
| Google `invalid_grant`/expired code | `BadCredentialsException` | 401 | `EUNAUTHORIZED` |
| Google network timeout/5xx | `GoogleAuthException` | 502 | `EBADGATEWAY` (mới thêm vào framework ErrorCode enum) |
| `id_token` sai signature/iss/aud | `BadCredentialsException` | 401 | `EUNAUTHORIZED` |
| `email_verified=false` | `BadCredentialsException` | 401 | `EUNAUTHORIZED` |
| User disabled (sync trả enabled=false) | `BadCredentialsException` | 401 | `EUNAUTHORIZED` |
| Race condition insert | `DuplicateKeyException` → re-fetch | — | — |

## Testing strategy

| Layer | Type | Approach |
|---|---|---|
| `GoogleTokenClient` | Unit | MockWebServer hoặc mock RestClient — verify form body, parse response, 4xx → BadCredentials, network err → GoogleAuthException |
| `GoogleIdTokenVerifier` | Unit | Generate RSA keypair test, sign JWT — pass/fail cases (sai aud, sai iss, expired, sai signature, email_verified=false) |
| `GoogleAuthService` | Unit | Mock các dep — verify orchestration (whitelist, email_verified check, sync call, JWT issue) |
| `GoogleUserSyncServiceImpl` | Integration | H2 schema có `google_sub`. Test 3 path (match by sub, link by email, create new) + race condition |
| `GoogleAuthController` | Integration | `@SpringBootTest` + `@MockBean` cho `GoogleTokenClient`, `GoogleIdTokenVerifier`. POST callback → 200 JWT; redirectUri lạ → 401; verifier null → 401 |
| End-to-end | Manual | Google Cloud Console OAuth client thật + dev compose |

Test schema H2: `application/api/src/test/resources/schema.sql` thêm cột `google_sub` + unique index (H2 supports partial index syntax).

## Out of scope

1. **PKCE** — defer; backend confidential client đã đủ. Khi cần native app, thêm `code_verifier` field.
2. **State validation server-side** — FE quản lý qua sessionStorage.
3. **Refresh token storage** — discard, không cần.
4. **Unlink Google** endpoint — defer.
5. **Profile sync** (cập nhật name/avatar từ Google về DB) — defer.
6. **UNIQUE constraint trên email** — đáng có, defer refactor riêng.
7. **OAuth provider khác** (Facebook, GitHub) — layout aggregator đã sẵn, defer implementation.
8. **Mobile endpoint `/auth/google/id-token`** — defer cho đến khi có mobile app.
9. **Audit `created_by` meaningful** cho user OAuth — defer.
10. **Rate limit `/auth/google/callback`** — dùng infra-level (Cloudflare/nginx), defer.

## Verification

- [ ] `mvn clean install -pl -mybatis-generator,-mybatis-schema-migration` BUILD SUCCESS sau refactor security aggregator.
- [ ] `application/api` build OK với `security-core` + `security-google`.
- [ ] Generated `User` entity có `googleSub` field sau `mybatis-generator:generate`.
- [ ] Integration test `GoogleAuthControllerIT`: 200 + JWT cho happy path, 401 cho redirectUri lạ, 401 cho invalid id_token.
- [ ] Integration test `GoogleUserSyncServiceImplIT`: 3 path (match by sub, link by email, create) all pass với H2.
- [ ] Dev compose: postgres + api up, set GOOGLE_OAUTH_* env vars, POST `/api/v1/auth/google/callback` với code thật từ Google → 200 + JWT.
- [ ] CLAUDE.md cập nhật: section "Security" thêm Google OAuth + đổi reference `security` → `security-core`.
- [ ] README.md cập nhật: cây thư mục `security/{core,google}/`, env vars Google.
- [ ] `grep -rE '<artifactId>security</artifactId>' .` không còn (đã đổi sang `security-core`).

## Notes

- **Convention `username = email` cho user OAuth-only**: dễ hiểu, unique tự nhiên, audit log đọc được. Nếu sau muốn `display_name` riêng → thêm column.
- **`password = ''` an toàn**: BCrypt match `("anything", "")` luôn false. Early-check trong AuthController dọn log warning.
- **Multi-provider tương lai**: thêm `security/<provider>/` cùng pattern. Mỗi provider có `<Provider>UserSyncService` port riêng. Khi cùng 1 user có thể link nhiều provider (Facebook + Google) → cân nhắc tách `user_oauth_providers` table (out of scope hiện tại).
- **Aggregator security/ pattern**: match `application/` aggregator vừa làm. Future provider thêm dễ.
- **`google-api-client` lib**: chính thức của Google, cache JWKS, retry, key rotation. Không tự code JWT verification.

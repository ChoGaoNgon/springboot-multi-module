# Google OAuth Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add "Sign in with Google" (OAuth 2.0 Authorization Code flow) to the existing JWT auth, refactoring `security/` into an aggregator (`security/core` + `security/google`) so other apps can reuse Google login independently of password login.

**Architecture:** Backend confidential client. FE sends an authorization `code` + `redirectUri` to `POST /api/v1/auth/google/callback`; BE exchanges the code with Google, verifies the `id_token` signature via the official `google-api-client` library, upserts a local user through a `GoogleUserSyncService` port (implemented by the app over MyBatis), then issues the same JWT as password login. `security/google` knows nothing about MyBatis or the DB schema.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Security 6, MyBatis 3.0.3, jjwt 0.12.6, `com.google.api-client:google-api-client` 2.7.0, PostgreSQL 16 (prod) / H2 PostgreSQL-mode (test), JUnit 5 + MockMvc + Mockito + `MockRestServiceServer`.

**Spec:** [docs/superpowers/specs/2026-06-13-google-oauth-design.md](../specs/2026-06-13-google-oauth-design.md)

**Build prerequisite (every `mvn` command):** JDK 21 is keg-only on this machine. Prefix all maven commands with:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```
The plan abbreviates this as `JAVA_HOME=…` after the first use.

---

## Deviations from the spec (read before starting)

These are intentional corrections discovered while grounding the plan in the real codebase. Where this plan and the spec disagree, **follow this plan**.

1. **No `UserRoleMapper`/`RoleMapper`/`UserAuthMapper.findSecurityUserById` exist.** The repo only has generated `UserMapper` + custom `CustomUserAuthMapper` (which has `findByUsername`, `findRoleCodes(userId)`, `findPermissionCodes(userId)`). The sync impl reuses `CustomUserAuthMapper` for roles/permissions and a new `CustomUserOAuthMapper` for the OAuth-specific lookups + the `user_roles` insert + the `roles.role_code → role_id` lookup. The user INSERT uses generated `UserMapper.insertSelective`.
2. **H2 does not support partial indexes.** Postgres (dev init + migration) uses `... WHERE google_sub IS NOT NULL`; the H2 test `schema.sql` uses a plain `CREATE UNIQUE INDEX` (both allow multiple NULLs — standard SQL treats NULLs as distinct in a unique index).
3. **`ErrorCode` has no `EBADGATEWAY`.** Task 3 adds it.
4. **Our `GoogleIdTokenVerifier` wraps the Google SDK verifier behind a constructor-injected delegate** so it is unit-testable without network/JWKS or RSA-signing gymnastics. The unit test mocks the delegate; signature verification itself is delegated to (and trusted from) Google's library.
5. **Migration scripts live at** `mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/scripts/` (the spec's `mybatis-schema-migration/scripts/` path is wrong).
6. **`security/` aggregator pom is modules-only** (no shared `<dependencies>`), so `jjwt`/`spring-security-starter` don't leak into `security-google`. The Java package stays `jp.co.htkk.security.*` — only Maven coordinates and the directory move.

---

## File Structure

### Moved (Task 1) — package unchanged (`jp.co.htkk.security.*`)
- `security/pom.xml` → rewritten to packaging `pom`, aggregator of `core` + `google`.
- `security/core/pom.xml` → new, `artifactId security-core`, holds the deps `security/pom.xml` currently declares.
- `security/src/**` → `git mv` to `security/core/src/**` (all existing Java + resources + the existing `JwtTokenServiceTest`).

### New module `security/google` (artifactId `security-google`, package `jp.co.htkk.security.google.*`)
- `config/GoogleOAuthProperties.java` — `@ConfigurationProperties("app.security.oauth.google")`.
- `config/GoogleOAuthAutoConfiguration.java` — beans + `@EnableConfigurationProperties`; registered in `AutoConfiguration.imports`.
- `web/GoogleAuthController.java` — `@RestController`, `POST /auth/google/callback`.
- `web/dto/GoogleCallbackRequest.java` — `{ code, redirectUri }` (validated).
- `service/GoogleAuthService.java` — orchestrates exchange → verify → sync → issue JWT.
- `service/GoogleTokenClient.java` — `RestClient` POST to Google token endpoint.
- `service/GoogleTokenResponse.java` — token endpoint response DTO.
- `service/GoogleIdTokenVerifier.java` — wraps Google SDK verifier; returns `GoogleUserInfo`.
- `service/GoogleAuthException.java` — `RuntimeException` for upstream/Google failures (→ 502).
- `port/GoogleUserSyncService.java` — port interface (app implements).
- `port/GoogleUserInfo.java` — `@Value @Builder` claims holder (lives with the port: it is part of the public contract the app's impl consumes).
- `resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

### Modified — `framework`
- `framework/src/main/java/jp/co/htkk/framework/exception/model/ErrorCode.java` — add `EBADGATEWAY`.

### Modified/new — `persistence`
- `persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserOAuthMapper.java` — new interface.
- `persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserOAuthMapper.xml` — new, colocated.
- generated `entity/.../generator/User.java` + `persistence/.../dao/generator/UserMapper.xml` — regenerated to include `google_sub` (Task 2).

### Modified/new — `application/api`
- `pom.xml` — `security` → `security-core`, add `security-google`.
- `security/GoogleUserSyncServiceImpl.java` — new, implements the port over MyBatis.
- `exception/ExceptionControllerAdvice.java` — add `GoogleAuthException → 502`.
- `resources/application.yml` — add `app.security.public-paths` + `app.security.oauth.google.*`.
- `src/test/resources/application-test.yml` — add Google OAuth test config.
- `src/test/resources/schema.sql` + `data.sql` — add `google_sub` column/index + an OAuth seed user.
- `src/test/java/.../security/GoogleUserSyncServiceImplIT.java` — new.
- `src/test/java/.../security/GoogleAuthControllerIT.java` — new.
- `src/test/java/.../security/AuthControllerOAuthUserIT.java` — new (empty-password guard).

### Modified — `security/core` (AuthController guard, Task 9)
- `security/core/src/main/java/jp/co/htkk/security/web/AuthController.java` — explicit empty-password rejection.

### Modified — DB seed / migration / docker / docs (Tasks 2, 12)
- `docker/postgres/init/01-create-users.sql`
- `mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/scripts/20260613120000_add_google_sub_to_users.sql` (new)
- `docker/api/docker-compose.{staging,production}.yaml`, `.env.example`
- `CLAUDE.md`, `README.md`

---

## Task 1: Refactor `security/` into aggregator `security/{core}`

No behavior change. Pure module move + rename so later tasks add `security/google` as a sibling. Build must stay green.

**Files:**
- Move: `security/src` → `security/core/src` (git mv)
- Create: `security/core/pom.xml`
- Rewrite: `security/pom.xml`
- Modify: `application/api/pom.xml:31` (`security` → `security-core`)

- [ ] **Step 1: Move the source tree into `core/`**

```bash
cd /Users/dangnh/Documents/project/base/springboot-multi-module
mkdir -p security/core
git mv security/src security/core/src
```

- [ ] **Step 2: Create `security/core/pom.xml`** (the deps the old `security/pom.xml` had; artifactId `security-core`)

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
        <relativePath>../../pom.xml</relativePath>
    </parent>
    <artifactId>security-core</artifactId>
    <name>security-core</name>

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

- [ ] **Step 3: Rewrite `security/pom.xml` as a modules-only aggregator**

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
    <packaging>pom</packaging>
    <name>security</name>

    <modules>
        <module>core</module>
        <module>google</module>
    </modules>
</project>
```

Note: this references the not-yet-existing `google` module. Create a placeholder so this task builds (real content arrives in Task 4):

- [ ] **Step 4: Create a minimal placeholder `security/google/pom.xml`**

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
        <relativePath>../../pom.xml</relativePath>
    </parent>
    <artifactId>security-google</artifactId>
    <name>security-google</name>

    <dependencies>
        <dependency>
            <groupId>jp.co.htkk</groupId>
            <artifactId>security-core</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: Point `application/api` at `security-core`**

In `application/api/pom.xml`, change the dependency (around line 29–32):

```xml
        <dependency>
            <groupId>jp.co.htkk</groupId>
            <artifactId>security-core</artifactId>
        </dependency>
```

- [ ] **Step 6: Build the whole reactor — must be green**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration
```
Expected: `BUILD SUCCESS`. `security-core`, `security-google` (empty), and `api` all build; `SecurityIntegrationTest` + `JwtTokenServiceTest` still pass.

- [ ] **Step 7: Verify no stale `security` artifact reference remains**

Run:
```bash
grep -rn "<artifactId>security</artifactId>" --include=pom.xml .
```
Expected: no output.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(security): split module into aggregator security/{core,google}

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Add `google_sub` to schema + regenerate the `User` entity

Adds the column to the dev seed, the prod migration, and the H2 test schema, then regenerates the generated `User`/`UserMapper` so the OAuth insert can persist `google_sub`. Regeneration needs the dev Postgres alive (the generator connects to `jdbc:postgresql://localhost:5432/helpo_step`, user `postgres`/`123456` — matching `docker/postgres/docker-compose.dev.yaml`).

**Files:**
- Modify: `docker/postgres/init/01-create-users.sql`
- Create: `mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/scripts/20260613120000_add_google_sub_to_users.sql`
- Modify: `application/api/src/test/resources/schema.sql`
- Regenerate: `entity/src/main/java/jp/co/htkk/entity/generator/User.java`, `persistence/src/main/java/jp/co/htkk/persistence/dao/generator/UserMapper.xml`

- [ ] **Step 1: Add column + partial index to the dev seed**

In `docker/postgres/init/01-create-users.sql`, add `google_sub` to the `users` `CREATE TABLE` (after the `password` line) and a partial unique index after the table. Replace the `users` table block (lines 4–12) with:

```sql
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL DEFAULT '',
    google_sub VARCHAR(255),
    created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    del_flag SMALLINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS users_google_sub_uk ON users (google_sub) WHERE google_sub IS NOT NULL;
```

- [ ] **Step 2: Create the prod migration script** (mybatis-migrations format)

Create `mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/scripts/20260613120000_add_google_sub_to_users.sql`:

```sql
-- // add_google_sub_to_users
-- Migration SQL that makes the change goes here.

ALTER TABLE users ADD COLUMN google_sub VARCHAR(255);
CREATE UNIQUE INDEX users_google_sub_uk ON users (google_sub) WHERE google_sub IS NOT NULL;

-- //@UNDO
-- SQL to undo the change goes here.

DROP INDEX IF EXISTS users_google_sub_uk;
ALTER TABLE users DROP COLUMN google_sub;
```

- [ ] **Step 3: Add column + plain unique index to the H2 test schema**

In `application/api/src/test/resources/schema.sql`, replace the `users` `CREATE TABLE` block (lines 7–15) with (H2 has no partial index — plain unique index, NULLs are distinct):

```sql
CREATE TABLE users (
    user_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL DEFAULT '',
    google_sub VARCHAR(255),
    created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    del_flag SMALLINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX users_google_sub_uk ON users (google_sub);
```

- [ ] **Step 4: Recreate the dev Postgres volume so the new init SQL runs**

The init script only runs on an empty volume. Run:
```bash
docker compose -f docker/postgres/docker-compose.dev.yaml down -v
docker compose -f docker/postgres/docker-compose.dev.yaml up -d
```
Wait for health, then verify the column exists:
```bash
docker exec pms-postgres psql -U postgres -d helpo_step -c "\d users" | grep google_sub
```
Expected: a `google_sub | character varying(255)` row.

- [ ] **Step 5: Regenerate the `User` entity + mapper against the live DB**

Run from inside the generator module (so `../` target paths resolve):
```bash
cd mybatis-generator
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn mybatis-generator:generate
cd ..
```
Expected: `BUILD SUCCESS`; `entity/.../generator/User.java` now has a `googleSub` field + `Column.googleSub`, and `persistence/.../dao/generator/UserMapper.xml` includes `google_sub` in its `BaseResultMap`, column lists, `insert`, and `insertSelective`.

- [ ] **Step 6: Verify regeneration captured the column**

Run:
```bash
grep -n "googleSub\|google_sub" entity/src/main/java/jp/co/htkk/entity/generator/User.java \
  persistence/src/main/java/jp/co/htkk/persistence/dao/generator/UserMapper.xml
```
Expected: matches in the entity (field + `Column` enum) and the XML (resultMap + insert/insertSelective). If empty, regeneration did not see the column — recheck Step 4.

- [ ] **Step 7: Build to confirm generated code compiles**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(db): add google_sub column to users + regenerate User entity

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Add `EBADGATEWAY` error code

**Files:**
- Modify: `framework/src/main/java/jp/co/htkk/framework/exception/model/ErrorCode.java`

- [ ] **Step 1: Add the enum constant**

In `ErrorCode.java`, add this line immediately after `EDUPLICATE("EDUPLICATE", "Duplicate data"),`:

```java
    EBADGATEWAY("EBADGATEWAY", "Upstream service error"),
```

- [ ] **Step 2: Build framework**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl framework compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add framework/src/main/java/jp/co/htkk/framework/exception/model/ErrorCode.java
git commit -m "feat(framework): add EBADGATEWAY error code

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Scaffold `security/google` module (port, DTOs, properties, exception)

Replace the placeholder pom with the real one and add the no-logic types so the module compiles and later TDD tasks have their collaborators.

**Files:**
- Rewrite: `security/google/pom.xml`
- Create: `security/google/src/main/java/jp/co/htkk/security/google/config/GoogleOAuthProperties.java`
- Create: `.../port/GoogleUserInfo.java`
- Create: `.../port/GoogleUserSyncService.java`
- Create: `.../web/dto/GoogleCallbackRequest.java`
- Create: `.../service/GoogleTokenResponse.java`
- Create: `.../service/GoogleAuthException.java`

- [ ] **Step 1: Rewrite `security/google/pom.xml`** (add google-api-client, validation, web, test deps)

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
        <relativePath>../../pom.xml</relativePath>
    </parent>
    <artifactId>security-google</artifactId>
    <name>security-google</name>

    <properties>
        <google-api-client.version>2.7.0</google-api-client.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>jp.co.htkk</groupId>
            <artifactId>security-core</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.api-client</groupId>
            <artifactId>google-api-client</artifactId>
            <version>${google-api-client.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Create `GoogleOAuthProperties`**

`security/google/src/main/java/jp/co/htkk/security/google/config/GoogleOAuthProperties.java`:

```java
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
```

- [ ] **Step 3: Create the port `GoogleUserInfo` (immutable claims holder)**

`security/google/src/main/java/jp/co/htkk/security/google/port/GoogleUserInfo.java`:

```java
package jp.co.htkk.security.google.port;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GoogleUserInfo {
    String sub;             // immutable Google account id; primary identifier
    String email;
    boolean emailVerified;
    String name;
    String picture;
}
```

- [ ] **Step 4: Create the port `GoogleUserSyncService`**

`security/google/src/main/java/jp/co/htkk/security/google/port/GoogleUserSyncService.java`:

```java
package jp.co.htkk.security.google.port;

import jp.co.htkk.security.port.SecurityUser;

/** Implemented by the consuming application: find/link/create the local user for a verified Google identity. */
public interface GoogleUserSyncService {
    /**
     * Find the existing user by Google sub, else link by verified email, else create a new user.
     *
     * @return a {@link SecurityUser} ready for {@code JwtTokenService.issue(...)}
     * @throws org.springframework.security.authentication.BadCredentialsException if the account is unusable
     */
    SecurityUser syncFromGoogle(GoogleUserInfo info);
}
```

- [ ] **Step 5: Create `GoogleCallbackRequest`**

`security/google/src/main/java/jp/co/htkk/security/google/web/dto/GoogleCallbackRequest.java`:

```java
package jp.co.htkk.security.google.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleCallbackRequest {
    @NotBlank
    private String code;
    @NotBlank
    private String redirectUri;
}
```

- [ ] **Step 6: Create `GoogleTokenResponse`**

`security/google/src/main/java/jp/co/htkk/security/google/service/GoogleTokenResponse.java`:

```java
package jp.co.htkk.security.google.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoogleTokenResponse {
    @JsonProperty("access_token")
    private String accessToken;
    @JsonProperty("id_token")
    private String idToken;
    @JsonProperty("token_type")
    private String tokenType;
    @JsonProperty("expires_in")
    private Long expiresIn;
    private String scope;
}
```

- [ ] **Step 7: Create `GoogleAuthException`**

`security/google/src/main/java/jp/co/htkk/security/google/service/GoogleAuthException.java`:

```java
package jp.co.htkk.security.google.service;

/** Upstream/Google service failure (network, 5xx) — mapped to HTTP 502 by the app's advice. */
public class GoogleAuthException extends RuntimeException {
    public GoogleAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 8: Build the module**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl security/google -am compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add security/google
git commit -m "feat(security-google): scaffold module — port, DTOs, properties, exception

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `GoogleTokenClient` (TDD)

Exchanges the authorization code for tokens against Google's token endpoint. Constructor takes a `RestClient.Builder` so the test can bind `MockRestServiceServer` to it.

**Files:**
- Create: `security/google/src/main/java/jp/co/htkk/security/google/service/GoogleTokenClient.java`
- Test: `security/google/src/test/java/jp/co/htkk/security/google/service/GoogleTokenClientTest.java`

- [ ] **Step 1: Write the failing test**

`security/google/src/test/java/jp/co/htkk/security/google/service/GoogleTokenClientTest.java`:

```java
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

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
```

- [ ] **Step 2: Run the test — verify it fails to compile (class missing)**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl security/google -am test -Dtest=GoogleTokenClientTest
```
Expected: compilation failure — `GoogleTokenClient` not found.

- [ ] **Step 3: Implement `GoogleTokenClient`**

`security/google/src/main/java/jp/co/htkk/security/google/service/GoogleTokenClient.java`:

```java
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
```

- [ ] **Step 4: Run the test — verify it passes**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl security/google -am test -Dtest=GoogleTokenClientTest
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add security/google
git commit -m "feat(security-google): GoogleTokenClient with code-exchange + error mapping

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `GoogleIdTokenVerifier` (TDD)

Wraps the Google SDK verifier (constructor-injected delegate) and maps the verified payload to `GoogleUserInfo`. Unit test mocks the delegate.

**Files:**
- Create: `security/google/src/main/java/jp/co/htkk/security/google/service/GoogleIdTokenVerifier.java`
- Test: `security/google/src/test/java/jp/co/htkk/security/google/service/GoogleIdTokenVerifierTest.java`

- [ ] **Step 1: Write the failing test**

`security/google/src/test/java/jp/co/htkk/security/google/service/GoogleIdTokenVerifierTest.java`:

```java
package jp.co.htkk.security.google.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import jp.co.htkk.security.google.port.GoogleUserInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.security.authentication.BadCredentialsException;

class GoogleIdTokenVerifierTest {

    private GoogleIdToken tokenWith(String sub, String email, boolean emailVerified, String name) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject(sub);
        payload.setEmail(email);
        payload.setEmailVerified(emailVerified);
        payload.set("name", name);
        GoogleIdToken token = mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(payload);
        return token;
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
```

- [ ] **Step 2: Run the test — verify it fails to compile**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl security/google -am test -Dtest=GoogleIdTokenVerifierTest
```
Expected: compilation failure — our `GoogleIdTokenVerifier` not found.

- [ ] **Step 3: Implement `GoogleIdTokenVerifier`**

`security/google/src/main/java/jp/co/htkk/security/google/service/GoogleIdTokenVerifier.java`:

```java
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
```

- [ ] **Step 4: Run the test — verify it passes**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl security/google -am test -Dtest=GoogleIdTokenVerifierTest
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add security/google
git commit -m "feat(security-google): GoogleIdTokenVerifier wrapping google-api-client

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `GoogleAuthService` (TDD)

Orchestrates: redirect-URI whitelist → code exchange → id_token verify → email_verified guard → sync user → issue JWT.

**Files:**
- Create: `security/google/src/main/java/jp/co/htkk/security/google/service/GoogleAuthService.java`
- Test: `security/google/src/test/java/jp/co/htkk/security/google/service/GoogleAuthServiceTest.java`

- [ ] **Step 1: Write the failing test**

`security/google/src/test/java/jp/co/htkk/security/google/service/GoogleAuthServiceTest.java`:

```java
package jp.co.htkk.security.google.service;

import jp.co.htkk.security.config.SecurityModuleProperties;
import jp.co.htkk.security.google.config.GoogleOAuthProperties;
import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.port.GoogleUserSyncService;
import jp.co.htkk.security.jwt.JwtTokenService;
import jp.co.htkk.security.port.SecurityUser;
import jp.co.htkk.security.web.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.security.authentication.BadCredentialsException;

class GoogleAuthServiceTest {

    private GoogleTokenClient tokenClient;
    private GoogleIdTokenVerifier verifier;
    private GoogleUserSyncService syncService;
    private JwtTokenService jwtTokenService;
    private GoogleOAuthProperties googleProps;
    private SecurityModuleProperties securityProps;
    private GoogleAuthService service;

    @BeforeEach
    void setUp() {
        tokenClient = mock(GoogleTokenClient.class);
        verifier = mock(GoogleIdTokenVerifier.class);
        syncService = mock(GoogleUserSyncService.class);
        googleProps = new GoogleOAuthProperties();
        googleProps.setAllowedRedirectUris(List.of("https://app/oauth/callback"));
        securityProps = new SecurityModuleProperties();
        securityProps.getJwt().setSecret("test-secret-0123456789-0123456789-0123456789");
        jwtTokenService = new JwtTokenService(securityProps);
        service = new GoogleAuthService(tokenClient, verifier, syncService, jwtTokenService, googleProps, securityProps);
    }

    private GoogleTokenResponse tokens() {
        GoogleTokenResponse r = new GoogleTokenResponse();
        r.setIdToken("idtok");
        return r;
    }

    @Test
    void handleCallback_happyPath_issuesJwt() {
        when(tokenClient.exchange("code", "https://app/oauth/callback")).thenReturn(tokens());
        when(verifier.verify("idtok")).thenReturn(GoogleUserInfo.builder()
                .sub("110169").email("u@gmail.com").emailVerified(true).name("U").build());
        when(syncService.syncFromGoogle(any())).thenReturn(SecurityUser.builder()
                .uid(101L).username("u@gmail.com").passwordHash("").enabled(true)
                .roles(Set.of("USER")).permissions(Set.of("USER_READ")).build());

        LoginResponse res = service.handleCallback("code", "https://app/oauth/callback");

        assertThat(res.getAccessToken()).isNotBlank();
        assertThat(res.getTokenType()).isEqualTo("Bearer");
        assertThat(res.getExpiresIn()).isEqualTo(securityProps.getJwt().getExpiration().toSeconds());
    }

    @Test
    void handleCallback_redirectUriNotWhitelisted_throws() {
        assertThatThrownBy(() -> service.handleCallback("code", "https://evil/callback"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void handleCallback_emailNotVerified_throws() {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(verifier.verify("idtok")).thenReturn(GoogleUserInfo.builder()
                .sub("110169").email("u@gmail.com").emailVerified(false).build());

        assertThatThrownBy(() -> service.handleCallback("code", "https://app/oauth/callback"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void handleCallback_userDisabled_throws() {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(verifier.verify("idtok")).thenReturn(GoogleUserInfo.builder()
                .sub("110169").email("u@gmail.com").emailVerified(true).build());
        when(syncService.syncFromGoogle(any())).thenReturn(SecurityUser.builder()
                .uid(101L).username("u@gmail.com").passwordHash("").enabled(false)
                .roles(Set.of()).permissions(Set.of()).build());

        assertThatThrownBy(() -> service.handleCallback("code", "https://app/oauth/callback"))
                .isInstanceOf(BadCredentialsException.class);
    }
}
```

- [ ] **Step 2: Run the test — verify it fails to compile**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl security/google -am test -Dtest=GoogleAuthServiceTest
```
Expected: compilation failure — `GoogleAuthService` not found.

- [ ] **Step 3: Implement `GoogleAuthService`**

`security/google/src/main/java/jp/co/htkk/security/google/service/GoogleAuthService.java`:

```java
package jp.co.htkk.security.google.service;

import jp.co.htkk.security.config.SecurityModuleProperties;
import jp.co.htkk.security.google.config.GoogleOAuthProperties;
import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.port.GoogleUserSyncService;
import jp.co.htkk.security.jwt.JwtTokenService;
import jp.co.htkk.security.port.SecurityUser;
import jp.co.htkk.security.web.dto.LoginResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;

@Slf4j
public class GoogleAuthService {

    private final GoogleTokenClient tokenClient;
    private final GoogleIdTokenVerifier idTokenVerifier;
    private final GoogleUserSyncService userSyncService;
    private final JwtTokenService tokenService;
    private final GoogleOAuthProperties googleProps;
    private final SecurityModuleProperties securityProps;

    public GoogleAuthService(GoogleTokenClient tokenClient, GoogleIdTokenVerifier idTokenVerifier,
                             GoogleUserSyncService userSyncService, JwtTokenService tokenService,
                             GoogleOAuthProperties googleProps, SecurityModuleProperties securityProps) {
        this.tokenClient = tokenClient;
        this.idTokenVerifier = idTokenVerifier;
        this.userSyncService = userSyncService;
        this.tokenService = tokenService;
        this.googleProps = googleProps;
        this.securityProps = securityProps;
    }

    public LoginResponse handleCallback(String code, String redirectUri) {
        if (!googleProps.getAllowedRedirectUris().contains(redirectUri)) {
            log.warn("Rejected Google callback with non-whitelisted redirectUri: {}", redirectUri);
            throw new BadCredentialsException("Invalid redirect URI");
        }

        GoogleTokenResponse tokens = tokenClient.exchange(code, redirectUri);
        GoogleUserInfo info = idTokenVerifier.verify(tokens.getIdToken());

        if (!info.isEmailVerified()) {
            throw new BadCredentialsException("Google email not verified");
        }
        if (info.getEmail() == null || info.getEmail().isBlank()) {
            throw new BadCredentialsException("Google email scope required");
        }

        SecurityUser user = userSyncService.syncFromGoogle(info);
        if (!user.isEnabled()) {
            throw new BadCredentialsException("Account disabled");
        }

        String token = tokenService.issue(user.getUid(), user.getUsername(), user.getRoles(), user.getPermissions());
        long expiresIn = securityProps.getJwt().getExpiration().toSeconds();
        return new LoginResponse(token, "Bearer", expiresIn);
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl security/google -am test -Dtest=GoogleAuthServiceTest
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add security/google
git commit -m "feat(security-google): GoogleAuthService orchestration

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `GoogleAuthController` + `GoogleOAuthAutoConfiguration`

Wire the HTTP endpoint and the auto-config beans (token client, id-token verifier delegate, service). Controller is a `@RestController` (component-scanned by the app), not a `@Bean` — matching the `AuthController` pattern in security-core.

**Files:**
- Create: `security/google/src/main/java/jp/co/htkk/security/google/web/GoogleAuthController.java`
- Create: `security/google/src/main/java/jp/co/htkk/security/google/config/GoogleOAuthAutoConfiguration.java`
- Create: `security/google/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Create `GoogleAuthController`**

`security/google/src/main/java/jp/co/htkk/security/google/web/GoogleAuthController.java`:

```java
package jp.co.htkk.security.google.web;

import jakarta.validation.Valid;
import jp.co.htkk.security.google.service.GoogleAuthService;
import jp.co.htkk.security.google.web.dto.GoogleCallbackRequest;
import jp.co.htkk.security.web.dto.LoginResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// @RestController so the app's component scan (scanBasePackages = "jp.co.htkk") registers it as a
// handler, mirroring AuthController in security-core. Not registered as a @Bean by the auto-config.
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

- [ ] **Step 2: Create `GoogleOAuthAutoConfiguration`**

`security/google/src/main/java/jp/co/htkk/security/google/config/GoogleOAuthAutoConfiguration.java`:

```java
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
```

- [ ] **Step 3: Register the auto-config**

Create `security/google/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
jp.co.htkk.security.google.config.GoogleOAuthAutoConfiguration
```

- [ ] **Step 4: Build the module**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl security/google -am test
```
Expected: `BUILD SUCCESS`, all Task 5–7 unit tests still pass.

- [ ] **Step 5: Commit**

```bash
git add security/google
git commit -m "feat(security-google): GoogleAuthController + auto-configuration

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: `AuthController` empty-password guard (security-core)

OAuth-only users have `password = ''`. `BCryptPasswordEncoder.matches("x", "")` already returns `false`, so they cannot password-login — but add an explicit early check for clean logs and a generic message. The behavioral test is an integration test in Task 11 (needs a DB), so this task is code-only + module build.

**Files:**
- Modify: `security/core/src/main/java/jp/co/htkk/security/web/AuthController.java`

- [ ] **Step 1: Add the early empty-password rejection**

In `AuthController.login(...)`, replace the body (the `if (...) throw ...` block and the lines after) with:

```java
    @PostMapping("/auth/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        SecurityUser user = userService.loadByUsername(request.getUsername());
        if (user == null || !user.isEnabled()
                || user.getPasswordHash() == null || user.getPasswordHash().isEmpty()
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // Empty hash => OAuth-only account; reject with the same generic message to avoid enumeration.
            throw new BadCredentialsException("Invalid username or password");
        }
        String token = tokenService.issue(user.getUid(), user.getUsername(), user.getRoles(), user.getPermissions());
        long expiresIn = props.getJwt().getExpiration().toSeconds();
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", expiresIn));
    }
```

- [ ] **Step 2: Build security-core**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl security/core test
```
Expected: `BUILD SUCCESS` (existing `JwtTokenServiceTest` passes).

- [ ] **Step 3: Commit**

```bash
git add security/core/src/main/java/jp/co/htkk/security/web/AuthController.java
git commit -m "feat(security-core): reject password login for empty-hash (OAuth-only) users

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Wire `application/api` (mapper, sync impl, advice, config)

**Files:**
- Modify: `application/api/pom.xml`
- Create: `persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserOAuthMapper.java`
- Create: `persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserOAuthMapper.xml`
- Create: `application/api/src/main/java/jp/co/htkk/api/security/GoogleUserSyncServiceImpl.java`
- Modify: `application/api/src/main/java/jp/co/htkk/api/exception/ExceptionControllerAdvice.java`
- Modify: `application/api/src/main/resources/application.yml`
- Modify: `application/api/src/test/resources/application-test.yml`

- [ ] **Step 1: Add `security-google` to `application/api/pom.xml`**

Add after the `security-core` dependency:

```xml
        <dependency>
            <groupId>jp.co.htkk</groupId>
            <artifactId>security-google</artifactId>
        </dependency>
```

- [ ] **Step 2: Create `CustomUserOAuthMapper` interface**

`persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserOAuthMapper.java`:

```java
package jp.co.htkk.persistence.dao.custom;

import org.apache.ibatis.annotations.Param;

public interface CustomUserOAuthMapper {

    /** Active user matching a Google sub, or null. */
    OAuthUserRow findByGoogleSub(@Param("googleSub") String googleSub);

    /** Active user matching an email, or null. */
    OAuthUserRow findByEmail(@Param("email") String email);

    /** Links a Google sub onto an existing user. Returns rows affected. */
    int linkGoogleSub(@Param("userId") Long userId, @Param("googleSub") String googleSub);

    /** role_id for an active role_code, or null. */
    Long findRoleIdByCode(@Param("roleCode") String roleCode);

    /** Inserts a user_roles row. Returns rows affected. */
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    class OAuthUserRow {
        public Long userId;
        public String username;
    }
}
```

- [ ] **Step 3: Create `CustomUserOAuthMapper.xml` (colocated, explicit resultMap)**

`persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserOAuthMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="jp.co.htkk.persistence.dao.custom.CustomUserOAuthMapper">

    <resultMap id="oauthUserRow" type="jp.co.htkk.persistence.dao.custom.CustomUserOAuthMapper$OAuthUserRow">
        <id column="user_id" jdbcType="BIGINT" property="userId"/>
        <result column="username" jdbcType="VARCHAR" property="username"/>
    </resultMap>

    <select id="findByGoogleSub" resultMap="oauthUserRow">
        SELECT user_id, username
        FROM users
        WHERE google_sub = #{googleSub} AND del_flag = 0
    </select>

    <select id="findByEmail" resultMap="oauthUserRow">
        SELECT user_id, username
        FROM users
        WHERE email = #{email} AND del_flag = 0
    </select>

    <update id="linkGoogleSub">
        UPDATE users SET google_sub = #{googleSub}
        WHERE user_id = #{userId} AND del_flag = 0
    </update>

    <select id="findRoleIdByCode" resultType="java.lang.Long">
        SELECT role_id FROM roles WHERE role_code = #{roleCode} AND del_flag = 0
    </select>

    <insert id="insertUserRole">
        INSERT INTO user_roles (user_id, role_id) VALUES (#{userId}, #{roleId})
    </insert>
</mapper>
```

- [ ] **Step 4: Create `GoogleUserSyncServiceImpl`**

`application/api/src/main/java/jp/co/htkk/api/security/GoogleUserSyncServiceImpl.java`:

```java
package jp.co.htkk.api.security;

import jp.co.htkk.entity.generator.User;
import jp.co.htkk.framework.enums.EDeleteFlag;
import jp.co.htkk.persistence.dao.custom.CustomUserAuthMapper;
import jp.co.htkk.persistence.dao.custom.CustomUserOAuthMapper;
import jp.co.htkk.persistence.dao.custom.CustomUserOAuthMapper.OAuthUserRow;
import jp.co.htkk.persistence.dao.generator.UserMapper;
import jp.co.htkk.security.google.config.GoogleOAuthProperties;
import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.port.GoogleUserSyncService;
import jp.co.htkk.security.port.SecurityUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleUserSyncServiceImpl implements GoogleUserSyncService {

    private final CustomUserOAuthMapper oauthMapper;
    private final CustomUserAuthMapper authMapper;   // reused for roles/permissions
    private final UserMapper userMapper;             // generated, single-table insert
    private final GoogleOAuthProperties props;

    @Override
    @Transactional
    public SecurityUser syncFromGoogle(GoogleUserInfo info) {
        Long userId;
        String username;

        OAuthUserRow row = oauthMapper.findByGoogleSub(info.getSub());
        if (row != null) {
            userId = row.userId;
            username = row.username;
        } else {
            row = oauthMapper.findByEmail(info.getEmail());
            if (row != null) {
                oauthMapper.linkGoogleSub(row.userId, info.getSub());
                userId = row.userId;
                username = row.username;
            } else {
                User created = createOauthUser(info);
                userId = created.getUserId();
                username = created.getUsername();
            }
        }

        Set<String> roles = new LinkedHashSet<>(authMapper.findRoleCodes(userId));
        Set<String> perms = new LinkedHashSet<>(authMapper.findPermissionCodes(userId));
        return SecurityUser.builder()
                .uid(userId)
                .username(username)
                .passwordHash("")
                .enabled(true)
                .roles(roles)
                .permissions(perms)
                .build();
    }

    private User createOauthUser(GoogleUserInfo info) {
        try {
            User u = new User();
            u.setUsername(info.getEmail());
            u.setEmail(info.getEmail());
            u.setPassword("");
            u.setGoogleSub(info.getSub());
            u.setDelFlag(EDeleteFlag.NOT_DELETED.getCode());
            userMapper.insertSelective(u);   // useGeneratedKeys populates userId

            Long roleId = oauthMapper.findRoleIdByCode(props.getDefaultRoleCode());
            if (roleId == null) {
                throw new IllegalStateException("Default OAuth role not found: " + props.getDefaultRoleCode());
            }
            oauthMapper.insertUserRole(u.getUserId(), roleId);
            return u;
        } catch (DuplicateKeyException e) {
            // Concurrent request inserted the same google_sub first; re-fetch the winner.
            log.warn("Concurrent OAuth user insert for google_sub={}, re-fetching", info.getSub());
            OAuthUserRow existing = oauthMapper.findByGoogleSub(info.getSub());
            if (existing == null) {
                throw new BadCredentialsException("User sync failed");
            }
            User u = new User();
            u.setUserId(existing.userId);
            u.setUsername(existing.username);
            return u;
        }
    }
}
```

Note: confirm `EDeleteFlag.NOT_DELETED.getCode()` returns a `Short` compatible with `User.setDelFlag(Short)`. It does (`framework/enums`, per CLAUDE.md). If `getCode()` returns `short`, autoboxing applies.

- [ ] **Step 5: Map `GoogleAuthException → 502` in the app advice**

In `application/api/src/main/java/jp/co/htkk/api/exception/ExceptionControllerAdvice.java`, add the import and a handler:

Add imports:
```java
import jp.co.htkk.security.google.service.GoogleAuthException;
```

Add this handler method inside the class (after `handleAuthentication`):
```java
    /**
     * Google upstream/network failure thrown from the controller. Map to 502 Bad Gateway with the
     * project envelope; without this it would fall through to the inherited Exception handler → 500.
     */
    @ExceptionHandler(GoogleAuthException.class)
    public ResponseEntity<ErrorResponse> handleGoogleAuth(GoogleAuthException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.of(HttpStatus.BAD_GATEWAY, "Upstream service error",
                List.of(ErrorCode.EBADGATEWAY.getErrorCode()));
        return new ResponseEntity<>(body, HttpStatus.BAD_GATEWAY);
    }
```

- [ ] **Step 6: Add public path + Google config to `application.yml`**

In `application/api/src/main/resources/application.yml`, replace the `app:` security block (lines 82–88) with:

```yaml
# Security module config
app:
  security:
    jwt:
      secret: ${JWT_SECRET}
      expiration: 30m
      renew-window: 3m
    public-paths:
      - /auth/google/**
    oauth:
      google:
        enabled: ${GOOGLE_OAUTH_ENABLED:true}
        client-id: ${GOOGLE_OAUTH_CLIENT_ID:}
        client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET:}
        allowed-redirect-uris:
          - ${GOOGLE_OAUTH_REDIRECT_URI:http://localhost:3000/oauth/callback}
        default-role-code: USER
        http-timeout: 5s
```

- [ ] **Step 7: Add Google test config to `application-test.yml`**

In `application/api/src/test/resources/application-test.yml`, replace the `app:` block with:

```yaml
app:
  security:
    jwt:
      secret: test-secret-0123456789-0123456789-0123456789
      expiration: 30m
      renew-window: 3m
    public-paths:
      - /auth/google/**
    oauth:
      google:
        enabled: true
        client-id: test-client-id
        client-secret: test-client-secret
        allowed-redirect-uris:
          - https://app/oauth/callback
        default-role-code: USER
        http-timeout: 5s
```

- [ ] **Step 8: Full build (excluding tooling modules)**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration
```
Expected: `BUILD SUCCESS`. (Existing `SecurityIntegrationTest` still passes; the app context now wires `GoogleUserSyncServiceImpl` + `GoogleAuthService`.)

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(api): wire Google OAuth — sync impl, mapper, advice, config

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Integration tests (TDD on H2)

Real Controller→Service→MyBatis→H2, no mocking of the persistence layer. `GoogleTokenClient` and `GoogleIdTokenVerifier` are `@MockBean` (we can't call Google in tests).

**Files:**
- Modify: `application/api/src/test/resources/data.sql` (add an OAuth-only seed user)
- Create: `application/api/src/test/java/jp/co/htkk/api/security/GoogleUserSyncServiceImplIT.java`
- Create: `application/api/src/test/java/jp/co/htkk/api/security/GoogleAuthControllerIT.java`
- Create: `application/api/src/test/java/jp/co/htkk/api/security/AuthControllerOAuthUserIT.java`

- [ ] **Step 1: Add an OAuth-only seed user to `data.sql`**

In `application/api/src/test/resources/data.sql`, change the `users` INSERT (lines 9–11) to add a third user with empty password + a google_sub, and give it the USER role. Replace lines 9–13 with:

```sql
INSERT INTO users (user_id, username, email, password, google_sub, created_by, updated_by)
VALUES (1, 'admin', 'admin@example.com', '$2a$10$eHdnG7uGKUnlpg/2H.zSv.qhmC.Bf0vRpGRyEmF4CTP3wE1vkSdaC', NULL, 0, 0),
       (2, 'normal', 'normal@example.com', '$2a$10$p6OTUNjOeNlBq3.Fx2G9V.OyWQAYTIzqbaoLFTl3rFvUNnqtOAKqi', NULL, 0, 0),
       (3, 'oauth@example.com', 'oauth@example.com', '', '999000111', 0, 0);

INSERT INTO user_roles (user_id, role_id) VALUES (1, 1), (2, 2), (3, 2);
```

- [ ] **Step 2: Write the sync-impl integration test**

`application/api/src/test/java/jp/co/htkk/api/security/GoogleUserSyncServiceImplIT.java`:

```java
package jp.co.htkk.api.security;

import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.port.GoogleUserSyncService;
import jp.co.htkk.security.port.SecurityUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GoogleUserSyncServiceImplIT {

    @Autowired private GoogleUserSyncService syncService;
    @Autowired private JdbcTemplate jdbc;

    private GoogleUserInfo info(String sub, String email) {
        return GoogleUserInfo.builder().sub(sub).email(email).emailVerified(true).name("X").build();
    }

    @Test
    void matchByGoogleSub_returnsExistingUser() {
        // user_id=3 seeded with google_sub=999000111
        SecurityUser u = syncService.syncFromGoogle(info("999000111", "oauth@example.com"));
        assertThat(u.getUid()).isEqualTo(3L);
        assertThat(u.getUsername()).isEqualTo("oauth@example.com");
        assertThat(u.getRoles()).contains("USER");
    }

    @Test
    void linkByEmail_setsGoogleSubOnExistingUser() {
        // admin (user_id=1) has no google_sub; link by email
        SecurityUser u = syncService.syncFromGoogle(info("sub-admin-link", "admin@example.com"));
        assertThat(u.getUid()).isEqualTo(1L);
        String linked = jdbc.queryForObject("SELECT google_sub FROM users WHERE user_id = 1", String.class);
        assertThat(linked).isEqualTo("sub-admin-link");
        assertThat(u.getRoles()).contains("ADMIN");
    }

    @Test
    void createNew_insertsUserAndDefaultRole() {
        SecurityUser u = syncService.syncFromGoogle(info("sub-brand-new", "newbie@gmail.com"));
        assertThat(u.getUid()).isNotNull();
        assertThat(u.getUsername()).isEqualTo("newbie@gmail.com");
        assertThat(u.getRoles()).containsExactly("USER");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE google_sub = 'sub-brand-new' AND password = ''", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Write the controller integration test**

`application/api/src/test/java/jp/co/htkk/api/security/GoogleAuthControllerIT.java`:

```java
package jp.co.htkk.api.security;

import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.service.GoogleIdTokenVerifier;
import jp.co.htkk.security.google.service.GoogleTokenClient;
import jp.co.htkk.security.google.service.GoogleTokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleAuthControllerIT {

    @Autowired private MockMvc mockMvc;
    @MockBean private GoogleTokenClient tokenClient;
    @MockBean private GoogleIdTokenVerifier idTokenVerifier;

    private GoogleTokenResponse tokens() {
        GoogleTokenResponse r = new GoogleTokenResponse();
        r.setIdToken("idtok");
        return r;
    }

    private String body(String code, String redirectUri) {
        return "{\"code\":\"" + code + "\",\"redirectUri\":\"" + redirectUri + "\"}";
    }

    @Test
    void callback_happyPath_returns200WithJwt() throws Exception {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(idTokenVerifier.verify("idtok")).thenReturn(GoogleUserInfo.builder()
                .sub("999000111").email("oauth@example.com").emailVerified(true).name("X").build());

        mockMvc.perform(post("/auth/google/callback").servletPath("/auth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("code", "https://app/oauth/callback")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void callback_redirectUriNotWhitelisted_returns401() throws Exception {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(idTokenVerifier.verify(anyString())).thenReturn(GoogleUserInfo.builder()
                .sub("999000111").email("oauth@example.com").emailVerified(true).build());

        mockMvc.perform(post("/auth/google/callback").servletPath("/auth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("code", "https://evil/callback")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callback_invalidIdToken_returns401() throws Exception {
        when(tokenClient.exchange(anyString(), anyString())).thenReturn(tokens());
        when(idTokenVerifier.verify(anyString())).thenThrow(new BadCredentialsException("bad"));

        mockMvc.perform(post("/auth/google/callback").servletPath("/auth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("code", "https://app/oauth/callback")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callback_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/auth/google/callback").servletPath("/auth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"redirectUri\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 4: Write the empty-password guard integration test**

`application/api/src/test/java/jp/co/htkk/api/security/AuthControllerOAuthUserIT.java`:

```java
package jp.co.htkk.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerOAuthUserIT {

    @Autowired private MockMvc mockMvc;

    @Test
    void oauthOnlyUser_cannotPasswordLogin_returns401() throws Exception {
        // user_id=3 (oauth@example.com) has password='' — any password must be rejected
        mockMvc.perform(post("/auth/login").servletPath("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"oauth@example.com\",\"password\":\"\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/login").servletPath("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"oauth@example.com\",\"password\":\"anything\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 5: Run the new integration tests**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl application/api test \
  -Dtest=GoogleUserSyncServiceImplIT,GoogleAuthControllerIT,AuthControllerOAuthUserIT
```
Expected: PASS (3 + 4 + 1 = 8 tests). If `GoogleUserSyncServiceImplIT.linkByEmail` mutates seed rows and bleeds into other tests, note that `spring.sql.init.mode=always` recreates the schema per context — but tests share one context. The `link` test only adds a google_sub to admin; the controller/guard tests don't depend on admin's google_sub, so order independence holds. If flakiness appears, add `@DirtiesContext` to `GoogleUserSyncServiceImplIT`.

- [ ] **Step 6: Full regression build**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration
```
Expected: `BUILD SUCCESS`, all modules' tests green.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "test(api): integration tests for Google OAuth callback + sync + empty-password guard

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Docs, env, staging/prod compose

**Files:**
- Modify: `CLAUDE.md` (Security section)
- Modify: `README.md`
- Modify/Create: `.env.example`
- Modify: `docker/api/docker-compose.staging.yaml`, `docker/api/docker-compose.production.yaml`

- [ ] **Step 1: Update `CLAUDE.md` Security section**

In the `### Security` section, update the module reference and add an OAuth bullet. Change the sentence that says the consuming app provides `SecurityUserService` to also note `GoogleUserSyncService`, and add:

```markdown
- **Module `security` là aggregator `security/{core,google}`**: `security-core` (login password + JWT + RBAC, artifactId `security-core`); `security-google` (Google OAuth, artifactId `security-google`). App khai dependency `security-core` (+ `security-google` nếu cần Google login).
- **Google OAuth**: `POST /auth/google/callback` `{code, redirectUri}` → BE exchange code với Google, verify `id_token` (google-api-client), upsert user qua port `GoogleUserSyncService` (app cung cấp bean — `GoogleUserSyncServiceImpl`), cấp JWT. Auto-link theo email (`email_verified=true`), auto-signup gán role `app.security.oauth.google.default-role-code` (mặc định `USER`). `redirectUri` phải nằm trong `allowed-redirect-uris`. User OAuth-only: `password=''` (không password-login được). Env: `GOOGLE_OAUTH_CLIENT_ID/SECRET/REDIRECT_URI`, `GOOGLE_OAUTH_ENABLED`.
```

Also update any `security` artifactId references in the module map table: change the `security` row to note it is now an aggregator of `security-core` + `security-google`.

- [ ] **Step 2: Update `README.md`**

Add `security/{core,google}/` to the module tree and document the Google env vars (`GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, `GOOGLE_OAUTH_REDIRECT_URI`, `GOOGLE_OAUTH_ENABLED`) and the `POST /api/v1/auth/google/callback` endpoint. (Match the existing README structure — read it first and mirror its formatting.)

- [ ] **Step 3: Update `.env.example`**

Append (create the file if it does not exist):

```
# --- Google OAuth (REQUIRED if GOOGLE_OAUTH_ENABLED=true) ---
GOOGLE_OAUTH_ENABLED=true
GOOGLE_OAUTH_CLIENT_ID=
GOOGLE_OAUTH_CLIENT_SECRET=
GOOGLE_OAUTH_REDIRECT_URI=https://app.example.com/oauth/callback
```

- [ ] **Step 4: Add fail-fast Google vars to staging/prod compose**

In `docker/api/docker-compose.staging.yaml` and `docker/api/docker-compose.production.yaml`, add to the api service `environment:` block (read the files first to match indentation/style):

```yaml
      GOOGLE_OAUTH_ENABLED: ${GOOGLE_OAUTH_ENABLED:-true}
      GOOGLE_OAUTH_CLIENT_ID: ${GOOGLE_OAUTH_CLIENT_ID:?}
      GOOGLE_OAUTH_CLIENT_SECRET: ${GOOGLE_OAUTH_CLIENT_SECRET:?}
      GOOGLE_OAUTH_REDIRECT_URI: ${GOOGLE_OAUTH_REDIRECT_URI:?}
```

- [ ] **Step 5: Verify the spec's grep gate**

Run:
```bash
grep -rn "<artifactId>security</artifactId>" --include=pom.xml .
```
Expected: no output (only `security-core` / `security-google` remain).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: document Google OAuth + security aggregator; staging/prod env

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Final verification

- [ ] **Step 1: Clean full build (excluding tooling modules)**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration
```
Expected: `BUILD SUCCESS`; all unit + integration tests pass.

- [ ] **Step 2: (Optional, needs Google credentials) Manual end-to-end on dev compose**

```bash
docker compose -f docker/postgres/docker-compose.dev.yaml up -d
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij \
GOOGLE_OAUTH_CLIENT_ID=<real> GOOGLE_OAUTH_CLIENT_SECRET=<real> \
GOOGLE_OAUTH_REDIRECT_URI=http://localhost:3000/oauth/callback \
  docker compose -f docker/api/docker-compose.dev.yaml up --build -d
curl http://localhost:9000/api/v1/actuator/health
# Then POST a real code:
# curl -XPOST http://localhost:9000/api/v1/auth/google/callback \
#   -H 'Content-Type: application/json' \
#   -d '{"code":"<code-from-google>","redirectUri":"http://localhost:3000/oauth/callback"}'
```
Expected: health `UP`; callback returns `{accessToken, tokenType:"Bearer", expiresIn}`.

- [ ] **Step 3: Walk the spec's Verification checklist**

Confirm each box in the spec's "Verification" section is satisfied (build green, generated `User.googleSub` present, ITs pass, docs updated, no `<artifactId>security</artifactId>` remaining).

---

## Self-Review (completed during planning)

**Spec coverage:** OAuth flow (Tasks 5–8), account linking + auto-signup (Task 10 sync impl + Task 11 ITs), schema + `google_sub` (Task 2), module aggregator refactor (Task 1), port contract `GoogleUserSyncService`/`GoogleUserInfo` (Task 4), config/env (Task 10, 12), security considerations — redirect whitelist + id_token verify + email_verified + empty-password guard (Tasks 7, 9), error handling incl. `EBADGATEWAY`→502 (Tasks 3, 10), testing strategy (Tasks 5–7 unit, 11 integration). All covered.

**Adaptations flagged** in the "Deviations from the spec" section (no `UserRoleMapper`/`RoleMapper`; H2 partial-index limitation; verifier delegate injection for testability; correct migration path; modules-only aggregator pom).

**Type consistency:** `GoogleUserInfo` (sub/email/emailVerified/name/picture), `GoogleTokenResponse` (idToken/accessToken/expiresIn), `GoogleAuthService.handleCallback(code, redirectUri)`, `GoogleTokenClient.exchange(code, redirectUri)`, `GoogleIdTokenVerifier.verify(idToken)`, `GoogleUserSyncService.syncFromGoogle(GoogleUserInfo)`, `CustomUserOAuthMapper` (findByGoogleSub/findByEmail/linkGoogleSub/findRoleIdByCode/insertUserRole + `OAuthUserRow`) — used consistently across tasks. `LoginResponse(accessToken, tokenType, expiresIn)` matches the existing class. `SecurityUser.builder()` fields match the existing record.

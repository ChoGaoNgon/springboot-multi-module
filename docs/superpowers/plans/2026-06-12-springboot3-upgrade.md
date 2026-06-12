# Spring Boot 3.3.x + Java 21 Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate this 11-module Maven base template from Spring Boot 2.7.4 / Java 11 to Spring Boot 3.3.x / Java 21, fix critical bugs found in review, and add a real integration-test harness (Controller → Service → MyBatis → H2, no mocks).

**Architecture:** Bottom-up Maven multi-module upgrade. Bump versions in the root + module POMs, run OpenRewrite to rewrite `javax.*` → `jakarta.*` across 66 files, reconcile remaining breaking changes by hand (springdoc v1→v2, MyBatis starter 3.x, MySQL driver coordinate), then build green. Integration tests boot the full `web/api` context against an in-memory H2 database (MySQL compatibility mode) seeded from `schema.sql` + `data.sql`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, MyBatis-Spring-Boot 3.0.x, PageHelper 2.1.x, springdoc-openapi 2.6.x, H2 (test), OpenRewrite (transient build plugin), JUnit 5 + MockMvc.

**Spec:** `docs/superpowers/specs/2026-06-12-springboot3-upgrade-design.md`. Working branch: `feature/springboot3-upgrade`.

---

## Prerequisites (do once, before Task 1)

- [ ] **JDK 21 installed and active.** The machine currently has JDK 17 on `PATH`. Install JDK 21 (e.g. `brew install openjdk@21` or SDKMAN `sdk install java 21-tem`) and point `JAVA_HOME` at it for this shell.

  Run: `java -version`
  Expected: `openjdk version "21..."` (or any 21.x build)

- [ ] **Maven works with JDK 21.**

  Run: `mvn -v`
  Expected: `Java version: 21...`

- [ ] **Confirm the baseline currently builds on the old toolchain is NOT required** — we only need JDK 21 from here on. Do not run the old build.

---

## File Structure

**Modified (POMs):**
- `pom.xml` — parent version, `java.version`, dependency version properties, transient OpenRewrite plugin (added then removed).
- `framework/pom.xml` — springdoc coordinate v1→v2; drop pinned `commons-lang3` if redundant.
- `dto/pom.xml` — springdoc coordinate v1→v2.
- `persistence/pom.xml` — MyBatis starter 3.x, MySQL driver coordinate, MyBatis test starter 3.x.
- `web/api/pom.xml` — add H2 test dependency.
- `batch/pom.xml` — no version edits (inherits), but verify it builds.

**Modified (Java / config — bug fixes):**
- `web/api/.../api/aspect/LoggingAspect.java` — fix pointcut package.
- `web/api/src/main/resources/application.yml`, `batch/src/main/resources/application.yml` — remove weak default DB credentials.
- (Conditional) `framework/.../exception/handler/impl/BindExceptionHandler.java`, `MethodArgumentNotValidExceptionHandler.java`, `web/api/.../api/exception/ExceptionConfiguration.java` — only if the validation-error test proves an NPE (see Task 9).

**Created (test harness):**
- `web/api/src/test/resources/application-test.yml` — H2 datasource + SQL init + test profile.
- `web/api/src/test/resources/schema.sql` — H2-compatible DDL (sanitized from migration DDL).
- `web/api/src/test/resources/data.sql` — deterministic seed rows.
- `web/api/src/test/java/jp/co/htkk/api/ApiContextLoadsTest.java` — context smoke test.
- `web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardMonthlyIntegrationTest.java` — DB-backed happy path.
- `web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardValidationIntegrationTest.java` — validation-error path (drives the MessageService bug check).
- `web/api/src/test/java/jp/co/htkk/api/controller/admin/UserSearchIntegrationTest.java` — controller wiring smoke (stub service, no DB).
- (Optional/contingency) `web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardDailyIntegrationTest.java` — exercises the `DATE()` SQL; may require Testcontainers.
- `batch/src/test/java/jp/co/htkk/batch/BatchContextLoadsTest.java` — batch context smoke test.

---

## Phase 1 — Version bumps & build green (no behavior change)

### Task 1: Bump the root POM to Boot 3.3.5 / Java 21

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Change the Spring Boot parent version**

In `pom.xml`, change the `<parent>` block version:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>
```

- [ ] **Step 2: Bump Java + dependency version properties**

In the `<properties>` block of `pom.xml`, set:

```xml
<!-- Java -->
<java.version>21</java.version>
<maven.compiler.target>${java.version}</maven.compiler.target>
<maven.compiler.source>${java.version}</maven.compiler.source>

<!-- spring -->
<springdoc.openapi-ui.version>2.6.0</springdoc.openapi-ui.version>

<!-- MySQL (driver now managed by the Boot BOM; property kept only for reference) -->
<mysql.connector.java.version>8.0.20</mysql.connector.java.version>

<!--Mybatis-->
<mybatis.spring.boot.version>3.0.3</mybatis.spring.boot.version>
<pagehelper.version>6.1.0</pagehelper.version>
<pagehelper.spring.boot.starter.version>2.1.0</pagehelper.spring.boot.starter.version>
<mybatis.generator.core.version>1.4.2</mybatis.generator.core.version>

<!-- Other libs -->
<lombok.version>1.18.34</lombok.version>
<commons.lang3.version>3.14.0</commons.lang3.version>
<guava.version>33.3.1-jre</guava.version>
<opencsv.version>5.9</opencsv.version>
<gson.version>2.11.0</gson.version>
<commons.io.version>2.16.1</commons.io.version>
<commons.text.version>1.12.0</commons.text.version>
```

Leave `commons.beanutils.version`, `commons.collections.version`, `mail.version`, `reactor.spring.version`, `mybatis.generator.maven.plugin.version`, `mybatis.generator.lombok.version` unchanged.

- [ ] **Step 3: Commit the root POM change**

```bash
git add pom.xml
git commit -m "build: bump parent to Spring Boot 3.3.5 and Java 21"
```

---

### Task 2: Update module POMs (persistence, framework, dto, web/api)

**Files:**
- Modify: `persistence/pom.xml`
- Modify: `framework/pom.xml`
- Modify: `dto/pom.xml`
- Modify: `web/api/pom.xml`

- [ ] **Step 1: persistence — MySQL driver coordinate + MyBatis test starter**

In `persistence/pom.xml`, replace the MySQL dependency:

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>
```

(no `<version>` — managed by the Boot 3 BOM), and bump the MyBatis test starter:

```xml
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter-test</artifactId>
    <version>3.0.3</version>
    <scope>test</scope>
</dependency>
```

The `mybatis-spring-boot-starter` and `pagehelper-spring-boot-starter` already read from the bumped properties — no change needed there.

- [ ] **Step 2: framework — springdoc v1 → v2 coordinate**

In `framework/pom.xml`, replace the springdoc dependency (currently pinned to `1.6.4`):

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>${springdoc.openapi-ui.version}</version>
</dependency>
```

- [ ] **Step 3: dto — springdoc v1 → v2 coordinate**

In `dto/pom.xml`, replace:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>${springdoc.openapi-ui.version}</version>
</dependency>
```

- [ ] **Step 4: web/api — add H2 for integration tests**

In `web/api/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

(no `<version>` — managed by the Boot 3 BOM).

- [ ] **Step 5: Commit the module POM changes**

```bash
git add persistence/pom.xml framework/pom.xml dto/pom.xml web/api/pom.xml
git commit -m "build: update module deps for Boot 3 (mysql-connector-j, mybatis 3.x, springdoc v2, h2)"
```

---

### Task 3: Rewrite `javax.*` → `jakarta.*` with OpenRewrite

**Files:**
- Modify (transient): `pom.xml` (add then remove the OpenRewrite plugin)
- Modify: 66 Java files (automatic)

- [ ] **Step 1: Add the OpenRewrite plugin to the root POM**

In `pom.xml`, inside `<build><plugins>`, add:

```xml
<plugin>
    <groupId>org.openrewrite.maven</groupId>
    <artifactId>rewrite-maven-plugin</artifactId>
    <version>5.42.0</version>
    <configuration>
        <activeRecipes>
            <recipe>org.openrewrite.java.migrate.jakarta.JavaxMigrationToJakarta</recipe>
        </activeRecipes>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>org.openrewrite.recipe</groupId>
            <artifactId>rewrite-migrate-java</artifactId>
            <version>2.29.0</version>
        </dependency>
    </dependencies>
</plugin>
```

- [ ] **Step 2: Run the recipe**

Run: `mvn -DskipTests rewrite:run`
Expected: BUILD SUCCESS, console lists changed files under `framework`, `web/api`, etc. (≈66 files). It rewrites imports such as `javax.validation.*` → `jakarta.validation.*`, `javax.servlet.http.*` → `jakarta.servlet.http.*`, `javax.sql.*` (unchanged — `javax.sql` is NOT migrated; that is correct, `javax.sql.DataSource` stays).

- [ ] **Step 3: Review the diff**

Run: `git diff --stat`
Expected: ~60+ Java files changed, only import lines. Spot-check one custom validator:

Run: `git diff framework/src/main/java/jp/co/htkk/framework/validation/PasswordValidator.java`
Expected: `import javax.validation...` lines became `import jakarta.validation...`.

- [ ] **Step 4: Remove the OpenRewrite plugin**

Delete the `<plugin>` block added in Step 1 from `pom.xml` (it is a one-shot tool, not a build dependency). Also delete the generated `rewrite.yml`/`.rewrite` cache files if any appear.

Run: `git status`
Expected: `pom.xml` modified (plugin removed), no stray rewrite artifacts staged.

- [ ] **Step 5: Commit the jakarta migration**

```bash
git add -A
git commit -m "refactor: migrate javax.* to jakarta.* via OpenRewrite"
```

---

### Task 4: Build all modules and fix residual compile errors

**Files:**
- Modify: any file the compiler flags (expected: few or none)

- [ ] **Step 1: Full build, no tests**

Run: `mvn clean install -DskipTests`
Expected: BUILD SUCCESS for all 11 modules.

- [ ] **Step 2: If compilation fails, fix by category**

Likely culprits and fixes (apply only if the compiler reports them):
- Any remaining `javax.annotation.PostConstruct` / `PreDestroy` → `jakarta.annotation.*`.
- `org.springframework.boot.web.servlet.support.SpringBootServletInitializer` import unchanged (still valid in Boot 3) — no action.
- springdoc annotations (`io.swagger.v3.oas.annotations.*`) are unchanged in v2 — no action.
- If a `HandlerInterceptorAdapter` is referenced anywhere, replace with plain `HandlerInterceptor` (the adapter was removed). Search: `grep -rn "HandlerInterceptorAdapter" --include=*.java .` and change `extends HandlerInterceptorAdapter` to `implements HandlerInterceptor`.

Re-run `mvn clean install -DskipTests` until BUILD SUCCESS.

- [ ] **Step 3: Commit any residual fixes**

```bash
git add -A
git commit -m "fix: resolve residual Boot 3 compilation issues"
```

(If Step 1 already passed with no edits, skip this commit.)

---

### Task 5: Fix Boot 3 config-property breaking changes + verify springdoc v2 (Swagger UI) wiring

**Files:**
- Modify: `web/api/src/main/resources/application.yml`
- Modify: `batch/src/main/resources/application.yml`
- Read: `web/api/src/main/java/jp/co/htkk/api/config/SpringdocConfig.java`
- Read: `web/api/src/main/java/jp/co/htkk/api/config/WebMvcConfiguration.java`
- Read: `framework/.../constant/CommonConstant.java` (the `PATH_PATTERNS.SWAGGER` value)

- [ ] **Step 1: Migrate the removed `spring.profiles` document property (Boot 3 breaking change)**

`web/api/src/main/resources/application.yml` ends with a second YAML document:

```yaml
---
spring:
  profiles: development, staging, production
```

In Boot 3 the `spring.profiles` property (used to scope a config document) was REMOVED and now throws `InvalidConfigDataPropertyException` at startup. Replace it with:

```yaml
---
spring:
  config:
    activate:
      on-profile: development | staging | production
```

Run: `grep -rn "spring.profiles" web/api/src/main/resources batch/src/main/resources`
Then apply the same `spring.config.activate.on-profile` replacement to any hit in `batch/src/main/resources/application.yml`.

- [ ] **Step 2: Confirm Swagger exclude patterns cover springdoc v2 paths**

springdoc v2 serves the UI at `/swagger-ui/index.html` and api-docs at the configured `/api-docs` (and `/v3/api-docs` by default). Open `CommonConstant.PATH_PATTERNS.SWAGGER` and confirm it includes patterns for `/swagger-ui/**`, `/api-docs/**`, and `/v3/api-docs/**`. If `/v3/api-docs/**` is missing, add it to that constant array.

- [ ] **Step 3: Build still green**

Run: `mvn -q -pl web/api -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add web/api/src/main/resources/application.yml batch/src/main/resources/application.yml framework/src/main/java/jp/co/htkk/framework/constant/CommonConstant.java
git commit -m "fix: migrate spring.profiles to on-profile and cover springdoc v2 api-docs path"
```

(Only stage the files you actually changed.)

---

## Phase 2 — Integration-test harness

### Task 6: Create the H2 test profile, schema, and seed data

**Files:**
- Create: `web/api/src/test/resources/application-test.yml`
- Create: `web/api/src/test/resources/schema.sql`
- Create: `web/api/src/test/resources/data.sql`

- [ ] **Step 1: Write `application-test.yml`**

This overrides only the datasource and turns on SQL init; everything else inherits from `application.yml`.

```yaml
spring:
  datasource:
    driver-class-name: org.h2.Driver
    url: jdbc:h2:mem:testdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER,MONTH,YEAR;DB_CLOSE_DELAY=-1
    username: sa
    password: ""
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
      data-locations: classpath:data.sql
```

- [ ] **Step 2: Write `schema.sql` (H2-compatible DDL)**

Sanitized from `mybatis-schema-migration/.../20221013045051_create_main_tables.sql` — same columns, but stripped of `ENGINE`, `CHARACTER SET`, `COLLATE`, `ROW_FORMAT`, and backticks (unsupported / unneeded in H2 MySQL mode). The `user` table and `month` column are H2 reserved words; they work here because the test datasource URL sets `NON_KEYWORDS=USER,MONTH,YEAR` (Task 6 Step 1).

```sql
CREATE TABLE step (
    step_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    step_count INT,
    sync_time TIMESTAMP NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    kcal INT,
    distance INT,
    total_time BIGINT,
    step_type SMALLINT DEFAULT 1 NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (step_id)
);

CREATE TABLE daily_point (
    daily_point_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    step_count INT,
    sync_date VARCHAR(10) NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    kcal INT,
    distance INT,
    total_time BIGINT,
    earn_point INT,
    step_event INT,
    point_event SMALLINT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (daily_point_id)
);

CREATE TABLE monthly_point (
    monthly_point_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    step_count INT,
    month VARCHAR(6) NOT NULL,
    kcal INT,
    distance INT,
    total_time BIGINT,
    earn_point INT,
    used_point INT,
    revocation_point INT,
    rest_point INT,
    step_event INT,
    point_event SMALLINT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (monthly_point_id)
);

CREATE TABLE change_point_history (
    change_point_history_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    amount_point INT NOT NULL,
    action_type SMALLINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (change_point_history_id)
);

CREATE TABLE transaction_point (
    transaction_point_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    transaction_type SMALLINT NOT NULL,
    amount_point INT,
    transaction_status SMALLINT NOT NULL,
    transaction_time TIMESTAMP NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    system_os VARCHAR(255) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (transaction_point_id)
);

CREATE TABLE transaction_point_history (
    transaction_point_history_id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_point_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    transaction_time TIMESTAMP NOT NULL,
    transaction_type SMALLINT NOT NULL,
    amount_point INT,
    transaction_status SMALLINT NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    total_point INT,
    rest_point INT,
    message TEXT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (transaction_point_history_id)
);

CREATE TABLE user (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    user_name VARCHAR(255) NOT NULL,
    contract_no VARCHAR(255) NOT NULL,
    contract_status SMALLINT NOT NULL,
    contract_term SMALLINT NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    email VARCHAR(255) NOT NULL,
    contract_time_start TIMESTAMP,
    contract_time_end TIMESTAMP,
    invitation_code VARCHAR(20),
    group_number SMALLINT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (user_id)
);
```

- [ ] **Step 3: Write `data.sql` (deterministic seed)**

Seeds `monthly_point` for month `202212`: two non-deleted rows (sums: earn_point 100+200=300, point_event 50+20=70, used_point 30+5=35, revocation_point 10+2=12), plus one deleted row and one other-month row that MUST be excluded by the query's `del_flag` / `month` filters. Also seeds one `user`.

```sql
INSERT INTO monthly_point
  (user_id, step_count, month, kcal, distance, total_time, earn_point, used_point, revocation_point, rest_point, step_event, point_event, created_by, created_at, updated_by, updated_at, del_flag)
VALUES
  (1, 1000, '202212', 0, 0, 0, 100, 30, 10, 0, 0, 50, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0),
  (1, 2000, '202212', 0, 0, 0, 200,  5,  2, 0, 0, 20, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0),
  (1, 9999, '202212', 0, 0, 0, 999, 99, 99, 0, 0, 99, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 1),
  (1, 3000, '202211', 0, 0, 0, 777, 77, 77, 0, 0, 77, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0);

INSERT INTO user
  (user_name, contract_no, contract_status, contract_term, phone_number, email, invitation_code, group_number, created_by, created_at, updated_by, updated_at, del_flag)
VALUES
  ('Test Taro', 'C-0001', 1, 1, '080111123456', 'taro@example.com', 'INV001', 3, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0);
```

- [ ] **Step 4: Commit the harness scaffolding**

```bash
git add web/api/src/test/resources/application-test.yml web/api/src/test/resources/schema.sql web/api/src/test/resources/data.sql
git commit -m "test: add H2 test profile, schema, and seed data"
```

---

### Task 7: Context-loads smoke test for web/api

**Files:**
- Create: `web/api/src/test/java/jp/co/htkk/api/ApiContextLoadsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package jp.co.htkk.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApiContextLoadsTest {

    @Test
    void contextLoads() {
        // Passes if the full application context boots on H2 under the "test" profile.
    }
}
```

- [ ] **Step 2: Run it**

Run: `mvn -pl web/api -am test -Dtest=ApiContextLoadsTest`
Expected: PASS. If it fails, read the root cause (common: H2 DDL syntax, a bean requiring a real MySQL connection, or springdoc v2 autoconfig). Fix the offending config and re-run before moving on.

- [ ] **Step 3: Commit**

```bash
git add web/api/src/test/java/jp/co/htkk/api/ApiContextLoadsTest.java
git commit -m "test: add web/api context-loads smoke test on H2"
```

---

### Task 8: DB-backed integration test — Dashboard monthly endpoint

**Files:**
- Create: `web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardMonthlyIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Exercises the full stack: `GET /admin/dashboard/monthly?monthSelected=202212` → `DashboardController` → `DashboardServiceImpl` → `CustomMonthlyPointMapper.getTotalPointInMonth` → H2. Asserts the summed, filtered values from `data.sql`. The context-path `/api/v1` is applied by MockMvc because the app sets `server.servlet.context-path`; use the path WITHOUT the context-path prefix in MockMvc (MockMvc bypasses the servlet context-path), i.e. `/admin/dashboard/monthly`.

```java
package jp.co.htkk.api.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardMonthlyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void monthlyPoint_aggregatesNonDeletedRowsForSelectedMonth() throws Exception {
        mockMvc.perform(get("/admin/dashboard/monthly").param("monthSelected", "202212"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.earnPoint.stepPoint").value(300))
                .andExpect(jsonPath("$.data.earnPoint.pointEvent").value(70))
                .andExpect(jsonPath("$.data.usedPoint.payPayPoint").value(35))
                .andExpect(jsonPath("$.data.usedPoint.mallPoint").value(0))
                .andExpect(jsonPath("$.data.revocationPoint").value(12));
    }
}
```

- [ ] **Step 2: Run it**

Run: `mvn -pl web/api -am test -Dtest=DashboardMonthlyIntegrationTest`
Expected: PASS. If the assertion values differ, the MyBatis SUM/filter path or the seed is off — debug the SQL against `data.sql` (the deleted row `del_flag=1` and the `202211` row must be excluded). Do NOT relax assertions to make it pass; fix the cause.

- [ ] **Step 3: Commit**

```bash
git add web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardMonthlyIntegrationTest.java
git commit -m "test: add DB-backed integration test for dashboard monthly endpoint"
```

---

### Task 9: Validation-error integration test (verifies the reported MessageService bug)

**Files:**
- Create: `web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardValidationIntegrationTest.java`
- Conditional Modify: `framework/.../exception/handler/impl/BindExceptionHandler.java`, `MethodArgumentNotValidExceptionHandler.java`, `web/api/.../api/exception/ExceptionConfiguration.java`

> **Why this task exists:** code review flagged a possible `NullPointerException` because the exception handlers use `@Autowired` field injection while being created via `new` in `@Bean` methods. In Spring, `@Bean`-returned instances ARE post-processed for `@Autowired` fields, so this may be a FALSE POSITIVE. This test settles it empirically. `MonthlyPointRequest.monthSelected` is annotated `@RequiredNotBlank` + `@DateFormat`, and the controller throws `BindException` on invalid input → routed to `BindExceptionHandler` (which calls `messageService.getMessage(...)`).

- [ ] **Step 1: Write the failing test**

```java
package jp.co.htkk.api.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void monthlyPoint_invalidMonth_returnsBadRequestNotServerError() throws Exception {
        // "abc" violates @DateFormat(YYYYMM); must yield a clean 400 from BindExceptionHandler,
        // NOT a 500 NPE. If this returns 500, the MessageService injection bug is real (see Step 3).
        mockMvc.perform(get("/admin/dashboard/monthly").param("monthSelected", "abc"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run it**

Run: `mvn -pl web/api -am test -Dtest=DashboardValidationIntegrationTest`
Expected (one of two outcomes):
- **PASS (400):** the `@Autowired` field on the `@Bean` handler WAS injected — the reported NPE is a false positive. Keep this test as a regression guard and SKIP Step 3 entirely.
- **FAIL (500 / NPE on `messageService`):** the bug is real — proceed to Step 3.

- [ ] **Step 3: (ONLY IF Step 2 returned 500) Convert handlers to constructor injection**

Change `BindExceptionHandler` to hold `MessageService` as a final field via constructor instead of `@Autowired`:

```java
@Slf4j
public class BindExceptionHandler implements IExceptionHandler<BindException> {

    private final MessageService messageService;

    public BindExceptionHandler(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public ErrorResponse handle(BindException exception, HttpServletRequest req) {
        // ...unchanged body...
    }
}
```

Apply the identical change to `MethodArgumentNotValidExceptionHandler` (constructor takes `MessageService`, drop the `@Autowired` field).

Then wire them in `ExceptionConfiguration` by injecting the bean into the `@Bean` methods:

```java
@Bean
public IExceptionHandler<BindException> bindExceptionHandler(MessageService messageService) {
    return new BindExceptionHandler(messageService);
}

@Bean
public IExceptionHandler<MethodArgumentNotValidException> methodArgumentNotValidExceptionIExceptionHandler(MessageService messageService) {
    return new MethodArgumentNotValidExceptionHandler(messageService);
}
```

Re-run: `mvn -pl web/api -am test -Dtest=DashboardValidationIntegrationTest`
Expected: PASS (400).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: assert validation errors return 400 (verify MessageService handler wiring)"
```

---

### Task 10: Controller-wiring smoke test — User search endpoint

**Files:**
- Create: `web/api/src/test/java/jp/co/htkk/api/controller/admin/UserSearchIntegrationTest.java`

> **Note:** `UserServiceImpl.searchUser` returns `null` and `UserResponse.of` returns an empty response (stub). This endpoint does NOT touch the DB. The test only verifies controller + binding + JSON serialization wiring survived the upgrade.

- [ ] **Step 1: Write the failing test**

```java
package jp.co.htkk.api.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void userSearch_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/user/userSearch").param("userId", "12345678999"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run it**

Run: `mvn -pl web/api -am test -Dtest=UserSearchIntegrationTest`
Expected: PASS (200).

- [ ] **Step 3: Commit**

```bash
git add web/api/src/test/java/jp/co/htkk/api/controller/admin/UserSearchIntegrationTest.java
git commit -m "test: add user search endpoint wiring smoke test"
```

---

### Task 11: (Contingency) Dashboard daily endpoint — H2 `DATE()` probe

**Files:**
- Create: `web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardDailyIntegrationTest.java`

> **Why contingency:** `getDailyPoint` calls `CustomTransactionPointMapper.getTotalPointLessThanDateAndStatusAndType`, whose SQL uses `DATE(tp.transaction_time)` — a MySQL function that may not exist in H2 MySQL mode. This task probes it. If H2 rejects `DATE()`, switch THIS test (only) to Testcontainers MySQL per the spec's fallback.

- [ ] **Step 1: Add seed rows for the daily path to `data.sql`**

Append to `web/api/src/test/resources/data.sql`:

```sql
INSERT INTO daily_point
  (user_id, step_count, sync_date, device_id, kcal, distance, total_time, earn_point, step_event, point_event, created_by, created_at, updated_by, updated_at, del_flag)
VALUES
  (1, 500, '2022-12-01', 'dev-1', 0, 0, 0, 40, 0, 10, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0);

INSERT INTO transaction_point
  (user_id, transaction_type, amount_point, transaction_status, transaction_time, device_id, system_os, created_by, created_at, updated_by, updated_at, del_flag)
VALUES
  (1, 1, 15, 1, TIMESTAMP '2022-11-30 10:00:00', 'dev-1', 'iOS', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0);
```

(`transaction_type`/`transaction_status` values must match `ETransactionType.PAYPAY` / `ETransactionStatus.SUCCESS` codes — open those enums and adjust the literals if their codes are not `1`.)

- [ ] **Step 2: Write the test**

```java
package jp.co.htkk.api.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardDailyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dailyPoint_returnsAggregatedBalance() throws Exception {
        mockMvc.perform(get("/admin/dashboard/daily").param("dateSelected", "2022-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
    }
}
```

(Open `DailyPointRequest` to confirm the query-param name is `dateSelected`; adjust if different.)

- [ ] **Step 3: Run it**

Run: `mvn -pl web/api -am test -Dtest=DashboardDailyIntegrationTest`
Expected: PASS. **If it fails with an H2 error like `Function "DATE" not found`:** apply the Testcontainers fallback in Step 4.

- [ ] **Step 4: (ONLY IF Step 3 failed on `DATE()`) Switch this test to Testcontainers MySQL**

Add to `web/api/pom.xml` (test scope):

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

Add the Testcontainers BOM to root `pom.xml` `<dependencyManagement>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.20.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Annotate `DashboardDailyIntegrationTest` with `@Testcontainers` and a `@Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withInitScript("mysql-schema.sql")`, register its JDBC props via `@DynamicPropertySource`, and add a `web/api/src/test/resources/mysql-schema.sql` (the ORIGINAL MySQL DDL from the migration script, which MySQL accepts verbatim). Requires Docker running. Keep all other tests on H2.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: add dashboard daily endpoint integration test (DATE() path)"
```

---

### Task 12: Batch module context-loads smoke test

**Files:**
- Create: `batch/src/test/java/jp/co/htkk/batch/BatchContextLoadsTest.java`
- Read first: `batch/src/main/java/jp/co/htkk/batch/` main application class (for the `@SpringBootTest` to find it) and `batch/src/main/resources/application.yml`

- [ ] **Step 1: Add an H2 test datasource for batch if it boots a datasource**

If batch boots a Spring datasource (it depends on `business-implementation` → `persistence`), create `batch/src/test/resources/application-test.yml` mirroring Task 6 Step 1, and add the H2 test dependency to `batch/pom.xml`:

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the test**

```java
package jp.co.htkk.batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BatchContextLoadsTest {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 3: Run it**

Run: `mvn -pl batch -am test -Dtest=BatchContextLoadsTest`
Expected: PASS. If batch auto-runs jobs on startup that need external resources, annotate the test to disable the runner (e.g. set the relevant scheduling/runner property to `false` in `application-test.yml`), then re-run.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: add batch context-loads smoke test"
```

---

## Phase 3 — Bug fixes & finalize

### Task 13: Fix the LoggingAspect pointcut package

**Files:**
- Modify: `web/api/src/main/java/jp/co/htkk/api/aspect/LoggingAspect.java:16`

- [ ] **Step 1: Fix the pointcut**

Change line 16 from:

```java
@Around("execution(* ko.alliex.energy.api.controller.*.*(..))")
```

to:

```java
@Around("execution(* jp.co.htkk.api.controller..*.*(..))")
```

(`..` covers the `admin` sub-package where the controllers live.)

- [ ] **Step 2: Verify aspect fires via an existing integration test**

Run: `mvn -pl web/api -am test -Dtest=DashboardMonthlyIntegrationTest`
Expected: PASS, and the test log now contains `Entering method getMonthlyPoint of class DashboardController` (the aspect now matches). Visually confirm the log line appears.

- [ ] **Step 3: Commit**

```bash
git add web/api/src/main/java/jp/co/htkk/api/aspect/LoggingAspect.java
git commit -m "fix: correct LoggingAspect pointcut to jp.co.htkk controllers"
```

---

### Task 14: Remove weak default DB credentials

**Files:**
- Modify: `web/api/src/main/resources/application.yml:24-25`
- Modify: `batch/src/main/resources/application.yml`

- [ ] **Step 1: Remove the inline default password (and username) in web/api**

In `web/api/src/main/resources/application.yml`, change:

```yaml
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD:123456}
```

to (no default — fail fast if the env var is missing in real environments):

```yaml
    username: ${MYSQL_USERNAME}
    password: ${MYSQL_PASSWORD}
```

- [ ] **Step 2: Apply the same change in `batch/src/main/resources/application.yml`**

Find the `username`/`password` datasource lines and remove the `:root` / `:123456` defaults the same way.

- [ ] **Step 3: Verify tests still pass (they use the H2 `application-test.yml`, not these defaults)**

Run: `mvn -pl web/api -am test -Dtest=ApiContextLoadsTest`
Expected: PASS (the test profile overrides the datasource, so the removed defaults do not affect tests).

- [ ] **Step 4: Commit**

```bash
git add web/api/src/main/resources/application.yml batch/src/main/resources/application.yml
git commit -m "fix: remove weak default DB credentials from application.yml"
```

---

### Task 15: Full verification & wrap-up

**Files:**
- Read: `README.md` (note version change)
- Read: `Dockerfile` (verify base image is JDK 21)

- [ ] **Step 1: Full clean build with all tests on Java 21**

Run: `mvn clean install`
Expected: BUILD SUCCESS across all 11 modules; all integration + smoke tests green.

- [ ] **Step 2: Boot the app and smoke-check Swagger + an endpoint**

Run (in one terminal):
```bash
MYSQL_USERNAME=root MYSQL_PASSWORD=changeit \
SPRING_DATASOURCE_URL='jdbc:h2:mem:devdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1' \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver \
mvn -pl web/api -am spring-boot:run
```
(Or point at a real MySQL.) Then in another terminal:

Run: `curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/api/v1/swagger-ui/index.html`
Expected: `200`.

Stop the app (Ctrl-C).

- [ ] **Step 3: Update the Dockerfile base image to JDK 21**

Open `Dockerfile`; if the build/runtime base image is JDK 11/17, bump it to a JDK 21 image (e.g. `eclipse-temurin:21-jre`). Adjust the `mvn package` build stage base to JDK 21 as well.

- [ ] **Step 4: Update README version notes**

In `README.md`, update the stated Spring Boot (3.3.5) and Java (21) versions and the build command if it referenced excluded modules.

- [ ] **Step 5: Final commit**

```bash
git add Dockerfile README.md
git commit -m "docs: update Dockerfile and README for Boot 3.3 / Java 21"
```

- [ ] **Step 6: Confirm the branch is clean and the build is green**

Run: `git status && mvn -q clean install`
Expected: clean working tree, BUILD SUCCESS.

---

## Verification Summary (maps to spec §8)

1. `mvn clean install` — all 11 modules compile and test on Java 21. ✔ Task 15.1
2. Integration tests green on H2 (monthly DB path, validation 400, user wiring, context loads); daily path on H2 or Testcontainers. ✔ Tasks 7–12
3. App boots; Swagger UI returns 200. ✔ Task 15.2
4. Batch boots on Boot 3 (context-loads test). ✔ Task 12

## Out of scope (deferred)

- Module **security** (sub-project B) and its dependencies: `LoginInfo`, `AuthorizationInterceptor`, `AuditInterceptor` `uid=0`.
- Non-critical quality (sub-project C): response-envelope standardization, empty `AbstractBaseService`, `UserController.getMonthlyPoint` misnomer, `.gitignore` secrets patterns, README content beyond version notes.

# PostgreSQL Re-baseline + Minimal User Skeleton — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strip the point-management demo domain down to a clean Spring Boot 3.3 / Java 21 / MyBatis skeleton running on **PostgreSQL** (tests on **H2 in PostgreSQL mode**), with one working minimal **User CRUD** as the example domain.

**Architecture:** Teardown the entire point domain across all modules, replace the MySQL datasource/driver/infra with PostgreSQL, and add a hand-written `users` table + `User` entity + `UserMapper` (Postgres/H2-PG compatible, explicit resultMap, numeric `del_flag`, `useGeneratedKeys`) wired through Service → Controller. Integration tests boot the full context against H2 in PostgreSQL mode.

**Tech Stack:** Java 21, Spring Boot 3.3.5, MyBatis-Spring-Boot 3.0.x, PageHelper, PostgreSQL (prod), H2 PostgreSQL mode (test), JUnit 5 + MockMvc.

**Spec:** `docs/superpowers/specs/2026-06-12-postgres-baseline-design.md`. Branch: `feature/postgres-baseline` (already created, off the Boot 3 branch).

**Build note:** All Maven commands use Java 21 and exclude the two DB-bound tooling modules:
`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn <goal> -pl -mybatis-generator,-mybatis-schema-migration`
(`mybatis-generator`/`mybatis-schema-migration` need a live DB; updated for correctness but not built in the reactor.)

---

## Key facts (verified during planning)

- **`MyBatisConfig` defines a custom `SqlSessionFactory`** → `application.yml` `mybatis.configuration.*` (map-underscore, jdbc-type-for-null) is **NOT applied**. The new `UserMapper` therefore uses an **explicit resultMap** and **never passes null params** (so the Postgres null-type gotcha cannot bite). `MyBatisConfig` is additionally hardened in code (Task 4) so future mappers are safe.
- **`AuditInterceptor`** auto-fills `createdBy`/`createdAt`/`updatedBy`/`updatedAt` (uid `0`, `Dates.now()`) on INSERT if those fields exist and are null → the `users` insert passes non-null audit values.
- **`AuthorizationInterceptor`** throws `FORBIDDEN` when `request.getServletPath()` is blank → MockMvc tests must call `.servletPath(...)` (real Tomcat sets it). Mapping still uses the request URI in Spring 6, so this does not break path matching.
- `batch` `Job0001` has only a **commented-out** point reference → batch needs no teardown.

---

## Phase 1 — Teardown the point domain

### Task 1: Delete point + old User domain across all modules

**Files (delete):**
- `entity/src/main/java/jp/co/htkk/entity/generator/` (entire dir: Step, DailyPoint, MonthlyPoint, TransactionPoint, TransactionPointHistory, ChangePointHistory, User + all `*Criteria`)
- `persistence/src/main/java/jp/co/htkk/persistence/dao/generator/` (entire dir) and `.../dao/custom/` (entire dir)
- `dto/src/main/java/jp/co/htkk/dto/admin/dashboard/` (entire), `.../dto/monthlypoint/`, `.../dto/dailypoint/`, `.../dto/transactionpoint/` (entire)
- `dto/src/main/java/jp/co/htkk/dto/admin/user/request/UserRequest.java`, `.../user/response/UserResponse.java`, `.../user/rst/UserRst.java` (keep `.../user/dxo/UserDxo.java`)
- `business/business-interface/.../service/DashboardService` is under `admin/`; delete `.../service/admin/DashboardService.java`, `.../service/MonthlyPointService.java`, `.../service/DailyPointService.java`, `.../service/TransactionPointService.java`, and `.../service/admin/UserService.java` (old)
- `business/business-implementation/.../service/impl/admin/DashboardServiceImpl.java`, `.../impl/MonthlyPointServiceImpl.java`, `.../impl/DailyPointServiceImpl.java`, `.../impl/TransactionPointServiceImpl.java`, `.../impl/admin/UserServiceImpl.java` (old)
- `business/business-implementation/src/test/java/jp/co/htkk/business/service/impl/MonthlyPointServiceImplTest.java`, `.../DailyPointServiceImplTest.java`
- `web/api/.../controller/admin/DashboardController.java`, `.../controller/admin/UserController.java` (old)
- `web/api/src/test/.../controller/admin/DashboardControllerTest.java`, `DashboardMonthlyIntegrationTest.java`, `DashboardDailyIntegrationTest.java`, `DashboardValidationIntegrationTest.java`, `UserSearchIntegrationTest.java`
- `web/api/src/test/java/jp/co/htkk/api/PointManagementSysApplicationTest.java` (redundant with `ApiContextLoadsTest`, and it has no `@ActiveProfiles("test")` so it would hit a real DB)
- `framework/src/main/java/jp/co/htkk/framework/enums/ETransactionType.java`, `ETransactionStatus.java`

**Keep:** `dto/common/**`, `dto/admin/user/dxo/UserDxo.java`, framework (incl. `EDeleteFlag`), all `web/api` config/aspect/interceptor, `web/api/src/test/.../ApiContextLoadsTest.java`, batch, skeleton.

- [ ] **Step 1: Delete the files**

```bash
cd /Users/dangnh/Documents/project/base/springboot-multi-module
git rm -r entity/src/main/java/jp/co/htkk/entity/generator
git rm -r persistence/src/main/java/jp/co/htkk/persistence/dao/generator persistence/src/main/java/jp/co/htkk/persistence/dao/custom
git rm -r dto/src/main/java/jp/co/htkk/dto/admin/dashboard dto/src/main/java/jp/co/htkk/dto/monthlypoint dto/src/main/java/jp/co/htkk/dto/dailypoint dto/src/main/java/jp/co/htkk/dto/transactionpoint
git rm dto/src/main/java/jp/co/htkk/dto/admin/user/request/UserRequest.java dto/src/main/java/jp/co/htkk/dto/admin/user/response/UserResponse.java dto/src/main/java/jp/co/htkk/dto/admin/user/rst/UserRst.java
git rm business/business-interface/src/main/java/jp/co/htkk/business/service/admin/DashboardService.java business/business-interface/src/main/java/jp/co/htkk/business/service/MonthlyPointService.java business/business-interface/src/main/java/jp/co/htkk/business/service/DailyPointService.java business/business-interface/src/main/java/jp/co/htkk/business/service/TransactionPointService.java business/business-interface/src/main/java/jp/co/htkk/business/service/admin/UserService.java
git rm business/business-implementation/src/main/java/jp/co/htkk/business/service/impl/admin/DashboardServiceImpl.java business/business-implementation/src/main/java/jp/co/htkk/business/service/impl/MonthlyPointServiceImpl.java business/business-implementation/src/main/java/jp/co/htkk/business/service/impl/DailyPointServiceImpl.java business/business-implementation/src/main/java/jp/co/htkk/business/service/impl/TransactionPointServiceImpl.java business/business-implementation/src/main/java/jp/co/htkk/business/service/impl/admin/UserServiceImpl.java
git rm business/business-implementation/src/test/java/jp/co/htkk/business/service/impl/MonthlyPointServiceImplTest.java business/business-implementation/src/test/java/jp/co/htkk/business/service/impl/DailyPointServiceImplTest.java
git rm web/api/src/main/java/jp/co/htkk/api/controller/admin/DashboardController.java web/api/src/main/java/jp/co/htkk/api/controller/admin/UserController.java
git rm web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardControllerTest.java web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardMonthlyIntegrationTest.java web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardDailyIntegrationTest.java web/api/src/test/java/jp/co/htkk/api/controller/admin/DashboardValidationIntegrationTest.java web/api/src/test/java/jp/co/htkk/api/controller/admin/UserSearchIntegrationTest.java
git rm web/api/src/test/java/jp/co/htkk/api/PointManagementSysApplicationTest.java
git rm framework/src/main/java/jp/co/htkk/framework/enums/ETransactionType.java framework/src/main/java/jp/co/htkk/framework/enums/ETransactionStatus.java
```

- [ ] **Step 2: Replace `endpoint.yml` with User endpoints**

Overwrite `web/api/src/main/resources/endpoint.yml`:

```yaml
endpoint:
  admin:
    user:
      create: /admin/users
      getById: /admin/users/{userId}
      list: /admin/users
```

- [ ] **Step 3: Compile to confirm no dangling references**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl -mybatis-generator,-mybatis-schema-migration clean install`
Expected: BUILD SUCCESS (no controllers/services for now; nothing references deleted classes — `UserDxo` is left as an orphan, which compiles).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: teardown point-management domain, keep skeleton"
```

---

## Phase 2 — New User domain (entity + mapper)

### Task 2: User entity + hand-written UserMapper

**Files:**
- Create: `entity/src/main/java/jp/co/htkk/entity/User.java`
- Create: `persistence/src/main/java/jp/co/htkk/persistence/dao/UserMapper.java`
- Create: `persistence/src/main/java/jp/co/htkk/persistence/dao/UserMapper.xml`

- [ ] **Step 1: Create the `User` entity**

`entity/src/main/java/jp/co/htkk/entity/User.java`:

```java
package jp.co.htkk.entity;

import lombok.Data;

import java.util.Date;

@Data
public class User {
    private Long userId;
    private String username;
    private String email;
    private Long createdBy;
    private Date createdAt;
    private Long updatedBy;
    private Date updatedAt;
    private Short delFlag;
}
```

- [ ] **Step 2: Create the `UserMapper` interface**

`persistence/src/main/java/jp/co/htkk/persistence/dao/UserMapper.java`:

```java
package jp.co.htkk.persistence.dao;

import jp.co.htkk.entity.User;

import java.util.List;

public interface UserMapper {

    int insertUser(User user);

    User selectById(Long userId);

    List<User> selectAll();
}
```

- [ ] **Step 3: Create the `UserMapper.xml`**

`persistence/src/main/java/jp/co/htkk/persistence/dao/UserMapper.xml` — explicit resultMap (custom `SqlSessionFactory` ignores `map-underscore`), numeric `del_flag = 0` (Postgres strict typing), `useGeneratedKeys` for the IDENTITY column:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="jp.co.htkk.persistence.dao.UserMapper">

    <resultMap id="userResultMap" type="jp.co.htkk.entity.User">
        <id column="user_id" jdbcType="BIGINT" property="userId"/>
        <result column="username" jdbcType="VARCHAR" property="username"/>
        <result column="email" jdbcType="VARCHAR" property="email"/>
        <result column="created_by" jdbcType="BIGINT" property="createdBy"/>
        <result column="created_at" jdbcType="TIMESTAMP" property="createdAt"/>
        <result column="updated_by" jdbcType="BIGINT" property="updatedBy"/>
        <result column="updated_at" jdbcType="TIMESTAMP" property="updatedAt"/>
        <result column="del_flag" jdbcType="SMALLINT" property="delFlag"/>
    </resultMap>

    <insert id="insertUser" parameterType="jp.co.htkk.entity.User"
            useGeneratedKeys="true" keyProperty="userId">
        INSERT INTO users (username, email, created_by, created_at, updated_by, updated_at, del_flag)
        VALUES (#{username}, #{email}, #{createdBy}, #{createdAt}, #{updatedBy}, #{updatedAt}, 0)
    </insert>

    <select id="selectById" resultMap="userResultMap" parameterType="java.lang.Long">
        SELECT user_id, username, email, created_by, created_at, updated_by, updated_at, del_flag
        FROM users
        WHERE user_id = #{userId} AND del_flag = 0
    </select>

    <select id="selectAll" resultMap="userResultMap">
        SELECT user_id, username, email, created_by, created_at, updated_by, updated_at, del_flag
        FROM users
        WHERE del_flag = 0
        ORDER BY user_id
    </select>
</mapper>
```

- [ ] **Step 4: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl persistence -am -o install` (or full build)
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add users table User entity and hand-written UserMapper"
```

---

### Task 3: User DTOs, Service, Controller

**Files:**
- Modify: `dto/src/main/java/jp/co/htkk/dto/admin/user/dxo/UserDxo.java`
- Create: `dto/src/main/java/jp/co/htkk/dto/admin/user/request/UserCreateRequest.java`
- Create: `dto/src/main/java/jp/co/htkk/dto/admin/user/response/UserResponse.java`
- Create: `dto/src/main/java/jp/co/htkk/dto/admin/user/response/UserListResponse.java`
- Create: `business/business-interface/src/main/java/jp/co/htkk/business/service/admin/UserService.java`
- Create: `business/business-implementation/src/main/java/jp/co/htkk/business/service/impl/admin/UserServiceImpl.java`
- Create: `web/api/src/main/java/jp/co/htkk/api/controller/admin/UserController.java`

- [ ] **Step 1: Update `UserDxo` with fields**

`dto/src/main/java/jp/co/htkk/dto/admin/user/dxo/UserDxo.java`:

```java
package jp.co.htkk.dto.admin.user.dxo;

import jp.co.htkk.dto.common.DXO;
import jp.co.htkk.dto.common.PRM;
import lombok.Data;

@Data
public class UserDxo extends DXO {

    private String username;
    private String email;

    @Override
    public <T extends PRM> T toPrm() {
        return null;
    }
}
```

- [ ] **Step 2: Create `UserCreateRequest`**

`dto/src/main/java/jp/co/htkk/dto/admin/user/request/UserCreateRequest.java`:

```java
package jp.co.htkk.dto.admin.user.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.dto.common.REQUEST;
import jp.co.htkk.framework.validation.annotation.RequiredNotBlank;
import lombok.Data;

import java.lang.reflect.InvocationTargetException;

@Data
public class UserCreateRequest extends REQUEST {

    @Schema(description = "User name", example = "taro")
    @RequiredNotBlank
    private String username;

    @Schema(description = "Email", example = "taro@example.com")
    @RequiredNotBlank
    private String email;

    @Override
    public UserDxo toDxo() throws IllegalAccessException, InstantiationException, InvocationTargetException {
        UserDxo dxo = new UserDxo();
        dxo.setUsername(this.username);
        dxo.setEmail(this.email);
        return dxo;
    }
}
```

- [ ] **Step 3: Create `UserResponse` and `UserListResponse`**

`dto/src/main/java/jp/co/htkk/dto/admin/user/response/UserResponse.java`:

```java
package jp.co.htkk.dto.admin.user.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jp.co.htkk.dto.common.RESPONSE;
import jp.co.htkk.entity.User;
import lombok.Data;

@Data
public class UserResponse extends RESPONSE {

    @JsonProperty("data")
    private UserData data;

    public static UserResponse of(User user) {
        UserResponse response = new UserResponse();
        response.setData(UserData.of(user));
        return response;
    }

    @Schema(name = "UserResponse.Data")
    @Data
    public static class UserData {
        private Long userId;
        private String username;
        private String email;

        public static UserData of(User user) {
            UserData d = new UserData();
            d.setUserId(user.getUserId());
            d.setUsername(user.getUsername());
            d.setEmail(user.getEmail());
            return d;
        }
    }
}
```

`dto/src/main/java/jp/co/htkk/dto/admin/user/response/UserListResponse.java`:

```java
package jp.co.htkk.dto.admin.user.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import jp.co.htkk.dto.common.RESPONSE;
import jp.co.htkk.entity.User;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

@Data
public class UserListResponse extends RESPONSE {

    @JsonProperty("data")
    private List<UserResponse.UserData> data;

    public static UserListResponse of(List<User> users) {
        UserListResponse response = new UserListResponse();
        response.setData(users.stream().map(UserResponse.UserData::of).collect(Collectors.toList()));
        return response;
    }
}
```

- [ ] **Step 4: Create `UserService` + `UserServiceImpl`**

`business/business-interface/src/main/java/jp/co/htkk/business/service/admin/UserService.java`:

```java
package jp.co.htkk.business.service.admin;

import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.entity.User;

import java.util.List;

public interface UserService {

    User createUser(UserDxo dxo);

    User getUser(Long userId);

    List<User> listUsers();
}
```

`business/business-implementation/src/main/java/jp/co/htkk/business/service/impl/admin/UserServiceImpl.java`:

```java
package jp.co.htkk.business.service.impl.admin;

import jp.co.htkk.business.service.AbstractBaseService;
import jp.co.htkk.business.service.admin.UserService;
import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.entity.User;
import jp.co.htkk.framework.exception.type.ServiceException;
import jp.co.htkk.persistence.dao.UserMapper;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class UserServiceImpl extends AbstractBaseService implements UserService {

    private final UserMapper userMapper;

    @Override
    public User createUser(UserDxo dxo) {
        User user = new User();
        user.setUsername(dxo.getUsername());
        user.setEmail(dxo.getEmail());
        // AuditInterceptor fills created_by/at, updated_by/at; useGeneratedKeys sets userId
        userMapper.insertUser(user);
        return user;
    }

    @Override
    public User getUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "User not found: " + userId);
        }
        return user;
    }

    @Override
    public List<User> listUsers() {
        return userMapper.selectAll();
    }
}
```

- [ ] **Step 5: Create `UserController`**

`web/api/src/main/java/jp/co/htkk/api/controller/admin/UserController.java`:

```java
package jp.co.htkk.api.controller.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jp.co.htkk.api.controller.AbstractBaseController;
import jp.co.htkk.business.service.admin.UserService;
import jp.co.htkk.dto.admin.user.request.UserCreateRequest;
import jp.co.htkk.dto.admin.user.response.UserListResponse;
import jp.co.htkk.dto.admin.user.response.UserResponse;
import jp.co.htkk.entity.User;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.InvocationTargetException;

@Tag(name = "UserController", description = "User CRUD API")
@RestController
@AllArgsConstructor
public class UserController extends AbstractBaseController {

    private final UserService userService;

    @Operation(summary = "Create a user")
    @PostMapping(value = "${endpoint.admin.user.create}")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserCreateRequest request, BindingResult bindingResult)
            throws BindException, InvocationTargetException, IllegalAccessException, InstantiationException {
        User user = (User) bindingResultWithValidate(bindingResult, request, userService::createUser);
        return ResponseEntity.status(HttpStatus.OK).body(UserResponse.of(user));
    }

    @Operation(summary = "Get a user by id")
    @GetMapping(value = "${endpoint.admin.user.getById}")
    public ResponseEntity<UserResponse> getById(@PathVariable("userId") Long userId) {
        return ResponseEntity.status(HttpStatus.OK).body(UserResponse.of(userService.getUser(userId)));
    }

    @Operation(summary = "List users")
    @GetMapping(value = "${endpoint.admin.user.list}")
    public ResponseEntity<UserListResponse> list() {
        return ResponseEntity.status(HttpStatus.OK).body(UserListResponse.of(userService.listUsers()));
    }
}
```

- [ ] **Step 6: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl -mybatis-generator,-mybatis-schema-migration clean install`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add minimal User CRUD (DTOs, service, controller, endpoints)"
```

---

## Phase 3 — PostgreSQL config & infra

### Task 4: Driver, datasource config, MyBatis hardening

**Files:**
- Modify: `pom.xml` (remove the now-unused MySQL property)
- Modify: `persistence/pom.xml` (driver)
- Modify: `web/api/src/main/resources/application.yml`, `batch/src/main/resources/application.yml`
- Modify: `web/api/src/main/java/jp/co/htkk/api/config/mybatis/MyBatisConfig.java`

- [ ] **Step 1: Swap the JDBC driver in `persistence/pom.xml`**

Replace the MySQL dependency block:

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

(no `<version>` — managed by the Spring Boot 3.3 BOM).

- [ ] **Step 2: Remove the dead MySQL property in root `pom.xml`**

Delete the line `<mysql.connector.java.version>8.0.20</mysql.connector.java.version>` (and its `<!-- MySQL ... -->` comment) from `pom.xml` `<properties>`.

- [ ] **Step 3: PostgreSQL datasource in `web/api/src/main/resources/application.yml`**

Replace the `spring.datasource` block:

```yaml
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:helpo_step}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
    hikari:
      maximum-pool-size: 10
```

And in the same file change the MyBatis null handling + add an explicit PageHelper dialect:

```yaml
  configuration:
    # ... keep existing keys ...
    jdbc-type-for-null: "NULL"
```

```yaml
pagehelper:
  auto-dialect: true
  helper-dialect: postgresql
  support-methods-arguments: true
```

- [ ] **Step 4: Apply the same datasource changes to `batch/src/main/resources/application.yml`**

Same `spring.datasource` block (note batch used `${MYSQL_HOST:localhost}` already), `jdbc-type-for-null: "NULL"`, and `pagehelper.helper-dialect: postgresql`.

- [ ] **Step 5: Harden `MyBatisConfig` so the custom factory applies the safe settings**

`web/api/src/main/java/jp/co/htkk/api/config/mybatis/MyBatisConfig.java` — set an explicit MyBatis `Configuration` (the custom factory bypasses `application.yml`):

```java
package jp.co.htkk.api.config.mybatis;

import jp.co.htkk.api.config.mybatis.intercept.AuditInterceptor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.boot.autoconfigure.SpringBootVFS;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@org.springframework.context.annotation.Configuration
public class MyBatisConfig {

    @Bean
    AuditInterceptor auditInterceptor() {
        return new AuditInterceptor();
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setVfs(SpringBootVFS.class);
        factoryBean.setPlugins(auditInterceptor());

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setJdbcTypeForNull(JdbcType.NULL); // Postgres-safe null handling
        configuration.setDefaultFetchSize(100);
        factoryBean.setConfiguration(configuration);

        return factoryBean.getObject();
    }
}
```

- [ ] **Step 6: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl -mybatis-generator,-mybatis-schema-migration clean install`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "build: switch datasource to PostgreSQL (driver, url, mybatis null handling, pagehelper dialect)"
```

---

## Phase 4 — Test harness on H2 (PostgreSQL mode) + User tests

### Task 5: H2 PostgreSQL test config, schema, and User integration tests

**Files:**
- Modify: `web/api/src/test/resources/application-test.yml`
- Replace: `web/api/src/test/resources/schema.sql`, `web/api/src/test/resources/data.sql`
- Modify: `batch/src/test/resources/application-test.yml`
- Create: `web/api/src/test/java/jp/co/htkk/api/controller/admin/UserCrudIntegrationTest.java`
- Create: `web/api/src/test/java/jp/co/htkk/api/controller/admin/UserValidationIntegrationTest.java`

- [ ] **Step 1: H2 PostgreSQL-mode test datasource (web/api)**

Overwrite `web/api/src/test/resources/application-test.yml`:

```yaml
spring:
  datasource:
    driver-class-name: org.h2.Driver
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
    username: sa
    password: ""
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
      data-locations: classpath:data.sql
```

- [ ] **Step 2: Replace `schema.sql` with the `users` table (Postgres DDL, runs on H2-PG)**

Overwrite `web/api/src/test/resources/schema.sql`:

```sql
DROP TABLE IF EXISTS users;
CREATE TABLE users (
    user_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    del_flag SMALLINT NOT NULL DEFAULT 0
);
```

- [ ] **Step 3: Replace `data.sql` with a seed user**

Overwrite `web/api/src/test/resources/data.sql`:

```sql
INSERT INTO users (username, email, created_by, created_at, updated_by, updated_at, del_flag)
VALUES ('seed-taro', 'seed-taro@example.com', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0);
```

- [ ] **Step 4: Batch test datasource to H2 PostgreSQL mode**

Overwrite `batch/src/test/resources/application-test.yml`:

```yaml
spring:
  datasource:
    driver-class-name: org.h2.Driver
    url: jdbc:h2:mem:batchtestdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
    username: sa
    password: ""
```

- [ ] **Step 5: Write the User CRUD integration test**

`web/api/src/test/java/jp/co/htkk/api/controller/admin/UserCrudIntegrationTest.java`:

```java
package jp.co.htkk.api.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
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
class UserCrudIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createThenGetThenList() throws Exception {
        // create
        MvcResult created = mockMvc.perform(post("/admin/users")
                        .servletPath("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").exists())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.email").value("alice@example.com"))
                .andReturn();

        long userId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("userId").asLong();

        // get by id
        mockMvc.perform(get("/admin/users/" + userId).servletPath("/admin/users/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value((int) userId))
                .andExpect(jsonPath("$.data.username").value("alice"));

        // list (seed-taro + alice => at least 2)
        mockMvc.perform(get("/admin/users").servletPath("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(Matchers.greaterThanOrEqualTo(2)));
    }
}
```

- [ ] **Step 6: Write the validation test**

`web/api/src/test/java/jp/co/htkk/api/controller/admin/UserValidationIntegrationTest.java`:

```java
package jp.co.htkk.api.controller.admin;

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
class UserValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void create_withBlankFields_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/admin/users")
                        .servletPath("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 7: Run the web/api tests**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn test -pl web/api`
Expected: `ApiContextLoadsTest`, `UserCrudIntegrationTest`, `UserValidationIntegrationTest` all PASS.
If `UserCrudIntegrationTest` create returns 500: check that H2 PostgreSQL mode accepts `GENERATED BY DEFAULT AS IDENTITY` and that `useGeneratedKeys` returns the id (it does on H2-PG). If get-by-id 403: ensure `.servletPath(...)` is set. Do not relax assertions — fix the cause.

- [ ] **Step 8: Run the batch test**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn test -pl batch -Dtest=BatchContextLoadsTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "test: H2 PostgreSQL-mode harness + User CRUD and validation integration tests"
```

---

## Phase 5 — Infra files, migration tooling, docs

### Task 6: docker-compose, schema migration, generator config, README

**Files:**
- Modify: `docker-compose.yaml`
- Modify: `mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/local.properties`
- Modify: `mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/scripts/20221013045051_create_main_tables.sql`
- Modify: `mybatis-schema-migration/pom.xml`, `mybatis-generator/pom.xml`, `mybatis-generator/src/main/resources/generatorConfig.xml`
- Modify: `README.md`

- [ ] **Step 1: PostgreSQL service in `docker-compose.yaml`**

Overwrite `docker-compose.yaml`:

```yaml
version: "3.9"
services:
  postgresDb:
    image: postgres:16
    volumes:
      - data:/var/lib/postgresql/data
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: 123456
      POSTGRES_DB: helpo_step
    ports:
      - 5432:5432
    container_name: postgresDb
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d helpo_step"]
      interval: 5s
      timeout: 5s
      retries: 5
  api:
    image: api:1.0.0
    build:
      context: ./
      dockerfile: Dockerfile
    ports:
      - 9000:9000
    environment:
      POSTGRES_HOST: postgresDb
      POSTGRES_PORT: 5432
      POSTGRES_DB: helpo_step
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: 123456
    depends_on:
      postgresDb:
        condition: service_healthy
    container_name: api

volumes:
  data:
```

- [ ] **Step 2: PostgreSQL connection for the migration tool**

In `mybatis-schema-migration/.../environments/local.properties` replace the JDBC block:

```properties
driver=org.postgresql.Driver
url=jdbc:postgresql://localhost:5432/helpo_step
username=postgres
password=123456
```

- [ ] **Step 3: Replace the migration DDL with the `users` table (Postgres)**

Overwrite the body of `mybatis-schema-migration/.../scripts/20221013045051_create_main_tables.sql` (keep the license header + the `-- // create_main_tables` / `-- //@UNDO` markers used by mybatis-migrations):

```sql
-- // create_main_tables
-- Migration SQL that makes the change goes here.

CREATE TABLE users (
    user_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    del_flag SMALLINT NOT NULL DEFAULT 0
);

-- //@UNDO
-- SQL to undo the change goes here.

DROP TABLE users;
```

- [ ] **Step 4: PostgreSQL driver for the two tooling modules**

In `mybatis-schema-migration/pom.xml` and `mybatis-generator/pom.xml`, replace the `mysql-connector-java`/`mysql-connector-j` dependency with:

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.4</version>
</dependency>
```

- [ ] **Step 5: PostgreSQL in `generatorConfig.xml`**

In `mybatis-generator/src/main/resources/generatorConfig.xml`:
- Change `<jdbcConnection driverClass="com.mysql.cj.jdbc.Driver" connectionURL="jdbc:mysql://localhost:3306/helpo_step" .../>` to `driverClass="org.postgresql.Driver" connectionURL="jdbc:postgresql://localhost:5432/helpo_step"` (update userId/password to `postgres`/`123456`).
- Remove the six point `<table>` entries (`step`, `daily_point`, `monthly_point`, `change_point_history`, `transaction_point`, `transaction_point_history`); keep only `<table tableName="users">` and change its `<generatedKey ... sqlStatement="MySql" .../>` to `sqlStatement="JDBC"`.

- [ ] **Step 6: Update README**

In `README.md`, change the Database row from MySQL to `PostgreSQL` (e.g. `16`), and update any domain description / run instructions that mention point-management or MySQL (`MYSQL_*` → `POSTGRES_*`, port 3306 → 5432).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "infra: PostgreSQL docker-compose, migration + generator config, README"
```

---

## Phase 6 — Final verification

### Task 7: Full build, tests, and live PostgreSQL CRUD

- [ ] **Step 1: Full clean build with all tests on Java 21**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn clean install -pl -mybatis-generator,-mybatis-schema-migration`
Expected: BUILD SUCCESS; `ApiContextLoadsTest`, `UserCrudIntegrationTest`, `UserValidationIntegrationTest`, `BatchContextLoadsTest` green.

- [ ] **Step 2: Start PostgreSQL and apply the schema**

```bash
docker compose up -d postgresDb
# wait for healthy, then create the users table (the app does NOT auto-create schema in prod):
docker exec -i postgresDb psql -U postgres -d helpo_step <<'SQL'
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    del_flag SMALLINT NOT NULL DEFAULT 0
);
SQL
```

- [ ] **Step 3: Boot the app against PostgreSQL (Java 21) and smoke-test User CRUD**

```bash
POSTGRES_HOST=localhost POSTGRES_PORT=5432 POSTGRES_DB=helpo_step POSTGRES_USER=postgres POSTGRES_PASSWORD=123456 \
/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java -jar web/api/target/api-0.0.1-SNAPSHOT.jar &
# wait for "Started PointManagementSysApplication"
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9000/api/v1/swagger-ui/index.html      # expect 200
curl -s -X POST http://localhost:9000/api/v1/admin/users -H 'Content-Type: application/json' -d '{"username":"bob","email":"bob@example.com"}'   # expect {"data":{"userId":...,"username":"bob",...}}
curl -s http://localhost:9000/api/v1/admin/users      # expect a JSON array with bob
```

Stop the app afterwards (`kill %1`) and `docker compose down`.

- [ ] **Step 4: Confirm clean tree and final build**

Run: `git status && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q clean install -pl -mybatis-generator,-mybatis-schema-migration`
Expected: clean working tree, BUILD SUCCESS.

---

## Verification Summary (maps to spec §8)

1. `mvn clean install` (excl. tooling modules) green on Java 21; H2-PostgreSQL tests pass. ✔ Task 7.1
2. `docker compose up` Postgres → app boots, Swagger 200, **User CRUD works** (POST create → GET list returns it). ✔ Task 7.2–7.3
3. Batch context loads on H2-PostgreSQL. ✔ Task 5.8 / 7.1

## Out of scope

- Renaming `groupId`/`artifactId`/package (`jp.co.htkk` / `point-management-sys`).
- Security module (sub-project B).
- Running MyBatis Generator against PostgreSQL (config repointed only).

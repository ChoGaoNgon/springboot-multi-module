# CLAUDE.md

Hướng dẫn cho AI/agent (và dev mới) làm việc trong repo này. Đọc kỹ trước khi sửa code.

## Tổng quan

`point-management-sys` — **base template Spring Boot 3 multi-module** (groupId `jp.co.htkk`) dùng để khởi tạo dự án Java mới. Kiến trúc multi-module, các module giao tiếp qua interface, quản lý version tập trung ở root `pom.xml`.

## Tech stack

| Hạng mục | Công nghệ | Version |
|---|---|---|
| Ngôn ngữ | Java (LTS) | **21** |
| Build | Apache Maven | 3.9 |
| Framework | Spring Boot | 3.3.5 |
| Security | Spring Security 6 + JWT (jjwt) | jjwt 0.12.6 |
| ORM | MyBatis (mybatis-spring-boot-starter) | 3.0.3 |
| Paging | PageHelper (`helper-dialect: postgresql`) | starter 2.1.0 |
| DB (prod) | PostgreSQL | 16 |
| DB (test) | H2 in-memory, `MODE=PostgreSQL` | — |
| API docs | springdoc-openapi-starter-webmvc-ui | 2.6.0 |
| Migration | mybatis-migrations-maven-plugin | 1.1.3 |
| Tiện ích | Lombok / Logback / commons-lang3 / guava / opencsv | Lombok 1.18.34 |
| Đóng gói | Docker multi-stage (`eclipse-temurin:21-jre`, non-root) | — |

## Lệnh build / test / run

> **Build cần JDK 21.** Trên máy này JDK 21 là keg-only:
> `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

```bash
# Build + test toàn bộ (LOẠI TRỪ 2 module tooling vì chúng cần DB sống để build)
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration

# Chỉ test application/api (integration test trên H2)
JAVA_HOME=... mvn test -pl application/api

# Chạy dev bằng docker compose (postgres + api, có seed RBAC)
# Bước 1: start postgres (tạo external network pms-net)
docker compose -f docker/postgres/docker-compose.dev.yaml up -d
# Bước 2: start api (join pms-net)
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij \
  docker compose -f docker/api/docker-compose.dev.yaml up --build -d
# Optional: start batch
docker compose -f docker/batch/docker-compose.dev.yaml up --build -d
# health (whitelist, không cần token):
curl http://localhost:9000/api/v1/actuator/health
```

- **Luôn loại `-mybatis-generator,-mybatis-schema-migration`** khi build full — chúng cần kết nối DB lúc build và sẽ fail offline.
- Context path API: `/api/v1/`. Port mặc định `9000`.

## Module map (thứ tự build & chiều phụ thuộc)

```
framework  → security → dto → entity → persistence → business → application/{api,batch}
                                                       (+ mybatis-generator, mybatis-schema-migration: tooling)
```

| Module | Trách nhiệm |
|---|---|
| `framework` | Core dùng chung: `ErrorResponse`/`ErrorCode`, `DefaultRestExceptionControllerAdvice`, validators (`framework/validation`), `MessageService` (i18n), `LoginInfo` (ThreadLocal principal), util. Không phụ thuộc module nào. |
| `security` | **Aggregator (packaging=pom)** gom `security/core` (artifactId `security-core`) + `security/google` (artifactId `security-google`). Java package giữ nguyên `jp.co.htkk.security.*`. |
| `security/core` | **Module security tái sử dụng, auto-config.** JWT HS256 + RBAC, deny-all-except-whitelist, login password, 401/403 handlers. App khác khai dependency `security-core` + set secret + cung cấp 1 bean `SecurityUserService`. |
| `security/google` | **Google OAuth 2.0** (Authorization Code flow), auto-config. App khai dependency `security-google` (kéo theo `security-core`) + set `GOOGLE_OAUTH_*` + cung cấp 1 bean `GoogleUserSyncService`. |
| `entity` | Entity MyBatis (vd `User`). |
| `dto` | DTO + flow `common` (`REQUEST/DXO/PRM/RST/RESPONSE`, `Envelope/Meta`). |
| `persistence` | DAO MyBatis (interface + XML colocated). |
| `business` | `business-interface` (service interface) + `business-implementation` (impl). |
| `application` | Aggregator POM (packaging=pom) gom các app deployable. Shared deps (`business-implementation`, `spring-boot-maven-plugin`) khai 1 chỗ. Thêm app mới: tạo `application/<tên>/`. |
| `application/api` | App chính (`PointManagementSysApplication`), controller, config, exception advice, adapter security. |
| `application/batch` | Batch job độc lập (KHÔNG khai dependency `security`). |
| `mybatis-generator` / `mybatis-schema-migration` | Tooling (generate code / DDL migration). |

## Coding rules / quy ước (BẮT BUỘC tuân theo)

### MyBatis (quan trọng nhất — dễ sai)
- `MyBatisConfig` (application/api) dùng **`SqlSessionFactory` tự cấu hình → BỎ QUA block `mybatis.configuration` trong `application.yml`.** Vì vậy:
  - Mapper **phải có `resultMap` tường minh** + file XML **đặt cùng package** với interface (vd `persistence/dao/custom/CustomUserAuthMapper.java` + `.xml`).
  - Các setting đặt trong code: `mapUnderscoreToCamelCase=true`, `jdbcTypeForNull=NULL` (bắt buộc cho PostgreSQL).
- Mapper interface đặt trong `jp.co.htkk.persistence.dao`, đăng ký qua `@MapperScan("jp.co.htkk.persistence.dao")` → mapper **viết tay KHÔNG cần** `@Mapper`. (Mapper generated có thể mang sẵn `@Mapper` do plugin itfsw sinh — vô hại, trùng với `@MapperScan`, không cần gỡ.)
- Soft delete: cột `del_flag` (`SMALLINT`, 0 = active); mọi query lọc `del_flag = 0`. INSERT dùng `useGeneratedKeys`.
- **KHÔNG hardcode tên cột / cờ trong `*Criteria` & order-by.** Tên cột lấy từ enum `Column` sinh trong entity: order-by dùng `<Entity>.Column.<field>.asc()/.desc()` (vd `User.Column.userId.asc()`), tên cột trần dùng `.value()`. Cờ soft-delete dùng `EDeleteFlag.NOT_DELETED.getCode()` (hoặc `DELETED`) ở `framework/enums` thay cho `(short)0/1`. Áp dụng cho mọi feature về sau.
- **Generated vs custom mapper:** Entity + mapper CRUD 1 bảng do `mybatis-generator` sinh ở `*.generator.*` (vd `entity.generator.User`, `dao.generator.UserMapper`) — **KHÔNG sửa tay, bị ghi đè khi generate**. **Ưu tiên method generated**: lọc/sort/đếm 1 bảng dùng `selectByExample`/`selectOneByExample` + `*Criteria` (vd lọc `del_flag=0` qua `createCriteria().andDelFlagEqualTo((short)0)`) — không cần custom. Chỉ tạo `Custom<Tên>Mapper` ở `persistence/dao/custom/` khi generated **không làm được** (join đa bảng, query đặc thù): đa bảng → standalone (vd `CustomUserAuthMapper`); 1 bảng đặc thù → `extends` mapper generated (MyBatis tự resolve method kế thừa về namespace cha). Sinh code: `cd mybatis-generator && mvn mybatis-generator:generate` (cần DB sống; chạy **TỪ TRONG thư mục module** để path `../` đúng; mỗi `<table>` nên đặt `domainObjectName`/`mapperName` cho tên gọn).

### DTO flow
- Luồng: `REQUEST.toDxo()` → `DXO.toPrm()` → service → `RST` → `RESPONSE`. Response bọc trong `Envelope`/`Meta`.
- Validate request qua `AbstractBaseController.bindingResultWithValidate(BindingResult, REQUEST, Function<DXO,?>)`.
- Validation dùng `jakarta.*`; custom validator ở `framework/validation`. Message key đồng bộ `jakarta`.

### Auditing
- `AuditInterceptor` (application/api) tự điền `createdBy/createdAt/updatedBy/updatedAt` khi INSERT/UPDATE, lấy uid từ `LoginInfo.fromContext()` (ThreadLocal, set bởi JWT filter). Field uid kiểu `Long` (fallback `0L` khi chưa auth).

### Error handling
- Envelope chuẩn: `ErrorResponse.of(HttpStatus, String message, List<String> errorCodes)` + enum `ErrorCode` (`EUNAUTHORIZED`="UNAUTHORIZED", `EACCES`="EACCES", `EINVAL_TOKEN`…).
- `@ControllerAdvice`: `DefaultRestExceptionControllerAdvice` (framework, có catch-all `Exception`→500) được kế thừa bởi `ExceptionControllerAdvice` (application/api). **Method-security ném exception BÊN TRONG controller** nên phải map ở application/api advice: `AccessDeniedException`→403, `AuthenticationException`→401 (nếu không sẽ bị catch-all nuốt thành 500).

### Security
- **Module `security` là aggregator `security/{core,google}`**: `security-core` (login password + JWT + RBAC) và `security-google` (Google OAuth). App khai dependency `security-core` (+ `security-google` nếu cần Google login). Java package giữ nguyên `jp.co.htkk.security.*`. Mọi reference cũ tới artifactId `security` đã đổi sang `security-core`.
- Module `security` **auto-config** (`@AutoConfiguration` + `AutoConfiguration.imports`). App `@SpringBootApplication(scanBasePackages="jp.co.htkk")` nên auto-config bị `AutoConfigurationExcludeFilter` loại khỏi component scan (đúng ý).
- `AuthController` là **`@RestController`** (được component scan bắt) — KHÔNG đăng ký lại bằng `@Bean` (plain `@Bean` + type-level `@RequestMapping` KHÔNG được map handler trong setup này → `/auth/login` không hoạt động).
- Phân quyền method-level: `@PreAuthorize("hasAuthority('PERMISSION_CODE')")` (vd `USER_READ`, `USER_WRITE`). Authority string = `permission_code` trong DB; role map thành `ROLE_<code>`.
- App tiêu thụ phải cung cấp 1 bean `SecurityUserService` (application/api: `SecurityUserServiceImpl` đọc qua `UserAuthMapper`). Secret: property `app.security.jwt.secret` (≥ 32 bytes); TTL `expiration` (30m), tự gia hạn khi còn ≤ `renew-window` (3m) qua header `X-New-Access-Token`.
- **Google OAuth** (`security-google`): `POST /auth/google/callback` `{code, redirectUri}` → BE exchange code với Google, verify `id_token` (lib `google-api-client`), upsert user qua port `GoogleUserSyncService` (app cung cấp bean — `GoogleUserSyncServiceImpl` ở application/api), cấp JWT giống password login. Auto-link theo email khi `email_verified=true`, auto-signup gán role `app.security.oauth.google.default-role-code` (mặc định `USER`). `redirectUri` phải nằm trong `allowed-redirect-uris` (chống open-redirect). User OAuth-only: `username=email`, `password=''` (KHÔNG password-login được — `AuthController` chặn empty hash). Schema: cột `users.google_sub` + unique partial index (regenerate `User` entity sau khi ALTER — **xoá `UserMapper.xml` trước khi `mybatis-generator:generate`** vì generator merge/append XML gây trùng `BaseResultMap`). Env: `GOOGLE_OAUTH_CLIENT_ID/SECRET/REDIRECT_URI`, `GOOGLE_OAUTH_ENABLED`. Lỗi Google upstream → `GoogleAuthException` → 502 (`EBADGATEWAY`).

### Config & secrets
- Cấu hình qua **env var + Spring profile** (`development` / `staging` / `production`). KHÔNG hardcode secret.
- Compose staging/prod dùng `${VAR:?}` → thiếu biến là fail ngay. Dev có default cho tiện.
- Profile chọn bằng `SPRING_PROFILES_ACTIVE`; YAML đa-document dùng `spring.config.activate.on-profile` (KHÔNG dùng `spring.profiles` kiểu Boot 2).

### Lombok & style
- `@Data` cho entity/DTO mutable; `@Value` cho immutable (vd `JwtPrincipal`, `LoginInfo`); `@Builder`, `@AllArgsConstructor`, `@Slf4j` theo nhu cầu.
- Package gốc `jp.co.htkk.*`. Controller `@RestController`, service impl `@Service`.

### Test
- **Integration test thật, KHÔNG mock** (Controller → Service → MyBatis → DB). DB test = H2 in-memory `MODE=PostgreSQL`, tự tạo qua `schema.sql` + `data.sql` (`spring.sql.init`).
- `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`. Endpoint đã bảo vệ → test phải login lấy token rồi gắn `Authorization: Bearer`.

### Migration (DDL)
- Dev: docker `docker/postgres/init/01-create-users.sql` chạy **1 lần khi volume rỗng** (không phải công cụ migration).
- Staging/prod: dùng `mybatis-schema-migration/migrate.sh <env> <up|down|status>` — script sinh env file từ biến môi trường (plugin 1.1.3 KHÔNG resolve `${...}`), chạy migration rồi xoá file. File `environments/{staging,production}.properties` bị gitignore + không commit.

### Docker multi-app
- 1 `Dockerfile` ở root, parameterized `ARG APP_MODULE` (mặc định `api`). Build app bất kỳ: `docker build --build-arg APP_MODULE=<module> -t <module>:tag .`
- **Quy ước cứng**: folder name dưới `application/` PHẢI = artifactId của module Maven (vd `application/api/` ↔ artifactId `api`). Dockerfile phụ thuộc convention này để tìm jar `${APP_MODULE}-${version}.jar`.
- Mỗi app có folder `docker/<app>/` chứa `docker-compose.{dev,staging,production}.yaml`. Add app mới: copy folder `docker/api/` thành `docker/<new-app>/` và sửa `APP_MODULE` + `image` + ports/env vars.
- Dev compose dùng external network `pms-net` (tạo bởi `docker/postgres/docker-compose.dev.yaml`). Postgres phải start TRƯỚC app compose.
- Khi app KHÔNG phải Spring Boot Java fat JAR (vd Node frontend, native CLI): không dùng root Dockerfile param — tạo Dockerfile riêng trong `application/<app>/Dockerfile`, compose tự khai báo `dockerfile: ../../application/<app>/Dockerfile`.

## Git / workflow
- Quy trình: **brainstorm → spec (`docs/superpowers/specs/`) → plan (`docs/superpowers/plans/`) → implement** theo task, commit thường xuyên.
- Conventional commits: `feat/fix/build/docs/chore/test/refactor(scope): ...`. Làm trên feature branch, không commit thẳng `main`.
- Remote `origin` = GitHub `ChoGaoNgon/springboot-multi-module` (URL sạch, không nhúng token).

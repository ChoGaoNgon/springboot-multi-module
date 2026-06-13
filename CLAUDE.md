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

# Chỉ test web/api (integration test trên H2)
JAVA_HOME=... mvn test -pl web/api

# Chạy dev bằng docker compose (postgres:16 + api, có seed RBAC)
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij docker compose up --build -d
# health (whitelist, không cần token):
curl http://localhost:9000/api/v1/actuator/health
```

- **Luôn loại `-mybatis-generator,-mybatis-schema-migration`** khi build full — chúng cần kết nối DB lúc build và sẽ fail offline.
- Context path API: `/api/v1/`. Port mặc định `9000`.

## Module map (thứ tự build & chiều phụ thuộc)

```
framework  → security → dto → entity → persistence → business → web/api → batch
                                                       (+ mybatis-generator, mybatis-schema-migration: tooling)
```

| Module | Trách nhiệm |
|---|---|
| `framework` | Core dùng chung: `ErrorResponse`/`ErrorCode`, `DefaultRestExceptionControllerAdvice`, validators (`framework/validation`), `MessageService` (i18n), `LoginInfo` (ThreadLocal principal), util. Không phụ thuộc module nào. |
| `security` | **Module security tái sử dụng, auto-config.** JWT HS256 + RBAC, deny-all-except-whitelist, login, 401/403 handlers. App khác chỉ cần khai dependency + set secret + cung cấp 1 bean `SecurityUserService`. |
| `entity` | Entity MyBatis (vd `User`). |
| `dto` | DTO + flow `common` (`REQUEST/DXO/PRM/RST/RESPONSE`, `Envelope/Meta`). |
| `persistence` | DAO MyBatis (interface + XML colocated). |
| `business` | `business-interface` (service interface) + `business-implementation` (impl). |
| `web/api` | App chính (`PointManagementSysApplication`), controller, config, exception advice, adapter security. |
| `batch` | Batch job độc lập (KHÔNG khai dependency `security`). |
| `mybatis-generator` / `mybatis-schema-migration` | Tooling (generate code / DDL migration). |

## Coding rules / quy ước (BẮT BUỘC tuân theo)

### MyBatis (quan trọng nhất — dễ sai)
- `MyBatisConfig` (web/api) dùng **`SqlSessionFactory` tự cấu hình → BỎ QUA block `mybatis.configuration` trong `application.yml`.** Vì vậy:
  - Mapper **phải có `resultMap` tường minh** + file XML **đặt cùng package** với interface (vd `persistence/dao/custom/CustomUserAuthMapper.java` + `.xml`).
  - Các setting đặt trong code: `mapUnderscoreToCamelCase=true`, `jdbcTypeForNull=NULL` (bắt buộc cho PostgreSQL).
- Mapper interface đặt trong `jp.co.htkk.persistence.dao`, đăng ký qua `@MapperScan("jp.co.htkk.persistence.dao")` (KHÔNG dùng `@Mapper` trên từng interface).
- Soft delete: cột `del_flag` (`SMALLINT`, 0 = active); mọi query lọc `del_flag = 0`. INSERT dùng `useGeneratedKeys`.
- **Generated vs custom mapper:** Entity + mapper CRUD 1 bảng do `mybatis-generator` sinh ở `*.generator.*` (vd `entity.generator.User`, `dao.generator.UserMapper`) — **KHÔNG sửa tay, bị ghi đè khi generate**. **Ưu tiên method generated**: lọc/sort/đếm 1 bảng dùng `selectByExample`/`selectOneByExample` + `*Criteria` (vd lọc `del_flag=0` qua `createCriteria().andDelFlagEqualTo((short)0)`) — không cần custom. Chỉ tạo `Custom<Tên>Mapper` ở `persistence/dao/custom/` khi generated **không làm được** (join đa bảng, query đặc thù): đa bảng → standalone (vd `CustomUserAuthMapper`); 1 bảng đặc thù → `extends` mapper generated (MyBatis tự resolve method kế thừa về namespace cha). Sinh code: `cd mybatis-generator && mvn mybatis-generator:generate` (cần DB sống; chạy **TỪ TRONG thư mục module** để path `../` đúng; mỗi `<table>` nên đặt `domainObjectName`/`mapperName` cho tên gọn).

### DTO flow
- Luồng: `REQUEST.toDxo()` → `DXO.toPrm()` → service → `RST` → `RESPONSE`. Response bọc trong `Envelope`/`Meta`.
- Validate request qua `AbstractBaseController.bindingResultWithValidate(BindingResult, REQUEST, Function<DXO,?>)`.
- Validation dùng `jakarta.*`; custom validator ở `framework/validation`. Message key đồng bộ `jakarta`.

### Auditing
- `AuditInterceptor` (web/api) tự điền `createdBy/createdAt/updatedBy/updatedAt` khi INSERT/UPDATE, lấy uid từ `LoginInfo.fromContext()` (ThreadLocal, set bởi JWT filter). Field uid kiểu `Long` (fallback `0L` khi chưa auth).

### Error handling
- Envelope chuẩn: `ErrorResponse.of(HttpStatus, String message, List<String> errorCodes)` + enum `ErrorCode` (`EUNAUTHORIZED`="UNAUTHORIZED", `EACCES`="EACCES", `EINVAL_TOKEN`…).
- `@ControllerAdvice`: `DefaultRestExceptionControllerAdvice` (framework, có catch-all `Exception`→500) được kế thừa bởi `ExceptionControllerAdvice` (web/api). **Method-security ném exception BÊN TRONG controller** nên phải map ở web/api advice: `AccessDeniedException`→403, `AuthenticationException`→401 (nếu không sẽ bị catch-all nuốt thành 500).

### Security
- Module `security` **auto-config** (`@AutoConfiguration` + `AutoConfiguration.imports`). App `@SpringBootApplication(scanBasePackages="jp.co.htkk")` nên auto-config bị `AutoConfigurationExcludeFilter` loại khỏi component scan (đúng ý).
- `AuthController` là **`@RestController`** (được component scan bắt) — KHÔNG đăng ký lại bằng `@Bean` (plain `@Bean` + type-level `@RequestMapping` KHÔNG được map handler trong setup này → `/auth/login` không hoạt động).
- Phân quyền method-level: `@PreAuthorize("hasAuthority('PERMISSION_CODE')")` (vd `USER_READ`, `USER_WRITE`). Authority string = `permission_code` trong DB; role map thành `ROLE_<code>`.
- App tiêu thụ phải cung cấp 1 bean `SecurityUserService` (web/api: `SecurityUserServiceImpl` đọc qua `UserAuthMapper`). Secret: property `app.security.jwt.secret` (≥ 32 bytes); TTL `expiration` (30m), tự gia hạn khi còn ≤ `renew-window` (3m) qua header `X-New-Access-Token`.

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
- Dev: docker `docker/postgres-init/01-create-users.sql` chạy **1 lần khi volume rỗng** (không phải công cụ migration).
- Staging/prod: dùng `mybatis-schema-migration/migrate.sh <env> <up|down|status>` — script sinh env file từ biến môi trường (plugin 1.1.3 KHÔNG resolve `${...}`), chạy migration rồi xoá file. File `environments/{staging,production}.properties` bị gitignore + không commit.

## Git / workflow
- Quy trình: **brainstorm → spec (`docs/superpowers/specs/`) → plan (`docs/superpowers/plans/`) → implement** theo task, commit thường xuyên.
- Conventional commits: `feat/fix/build/docs/chore/test/refactor(scope): ...`. Làm trên feature branch, không commit thẳng `main`.
- Remote `origin` = GitHub `ChoGaoNgon/springboot-multi-module` (URL sạch, không nhúng token).

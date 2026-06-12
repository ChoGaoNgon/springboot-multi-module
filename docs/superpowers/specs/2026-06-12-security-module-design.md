# Design Spec — Module `security` tái sử dụng (Spring Security 6 + JWT + RBAC)

> Sub-project **B** trong lộ trình A → B → C. Ngày: 2026-06-12. Xây trên nhánh `feature/postgres-baseline` (Spring Boot 3.3.5 / Java 21 / PostgreSQL / MyBatis).

## 1. Bối cảnh (Context)

Yêu cầu gốc của chủ dự án: *"tạo 1 module security riêng và khi cần dùng cho tầng application nào thì chỉ việc khai dependency vào là dùng được"*.

Hiện trạng: security chỉ là khung rỗng — `AuthorizationInterceptor` luôn `return true`, `LoginInfo.fromContext()` trả `null`, `AuditInterceptor` ghi audit uid = 0, bảng `users` chưa có password, chưa có Spring Security/JWT dependency. `CommonConstant` đã có sẵn constants `Bearer`/`access_token`/claim `uid` — đúng ý đồ token-based ban đầu.

Kết quả mong muốn: module Maven `security` độc lập; bất kỳ web app nào trong (hoặc ngoài) base này khai dependency là có ngay: JWT authentication, RBAC authorization, login API, token tự gia hạn, lỗi 401/403 đúng envelope chuẩn — và app chỉ phải cung cấp một bean nguồn dữ liệu user.

## 2. Quyết định đã chốt (qua brainstorming)

| Hạng mục | Quyết định |
|---|---|
| Cơ chế xác thực | **JWT tự cấp (HS256), stateless** — module có login + validate |
| Phân quyền | **RBAC đầy đủ**: `roles`, `permissions`, `user_roles`, `role_permissions` |
| Token lifecycle | **1 access token, tự gia hạn**: còn hạn < renew-window (mặc định **3 phút**) → server phát token mới qua response header; thời hạn cấu hình qua property |
| Tư thế mặc định | **Khóa tất cả trừ whitelist** (secure-by-default) |
| Cách plug-in | **Spring Boot auto-configuration + port `SecurityUserService`** — khai dependency là chạy; app cung cấp dữ liệu user |
| Nhánh làm việc | `feature/security-module` (tách từ `feature/postgres-baseline`) |

## 3. Module `security` (mới: `jp.co.htkk:security`)

**Dependencies:** `spring-boot-starter-security`, `jjwt` 0.12.x (`jjwt-api`/`jjwt-impl`/`jjwt-jackson`), `framework` (tái dùng `ErrorResponse` envelope + `LoginInfo`). Web stack (servlet) do app tiêu thụ cung cấp.

**Thành phần:**

- **`SecurityModuleAutoConfiguration`** — đăng ký qua `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; `@ConditionalOnWebApplication(type = SERVLET)` (batch không bị ảnh hưởng kể cả khi dính transitive); `@EnableMethodSecurity` (bật `@PreAuthorize`).
- **`SecurityModuleProperties`** — prefix `app.security`:
  - `enabled` (default `true`)
  - `jwt.secret` (bắt buộc khi enabled; HS256, ≥ 32 bytes)
  - `jwt.expiration` (Duration, default `30m`)
  - `jwt.renew-window` (Duration, default `3m`)
  - `public-paths` (list, cộng thêm vào whitelist mặc định: `/auth/login`, `/swagger-ui/**`, `/api-docs/**`, `/v3/api-docs/**`, `/actuator/health`)
- **`JwtTokenService`** — issue / validate / renew. Claims: `sub` = username, `uid`, `roles` (codes), `perms` (codes) — tái dùng tên claim sẵn có trong `CommonConstant` khi trùng khớp.
- **`JwtAuthenticationFilter`** — đọc `Authorization: Bearer <token>`:
  1. Hợp lệ → dựng `Authentication` (authorities = `ROLE_<role>` + permission codes) vào `SecurityContextHolder` **và** set `LoginInfo` ThreadLocal (uid, username); clear cả hai sau request (`finally`).
  2. **Renew:** nếu thời gian còn lại của token < `renew-window` → phát token mới (copy claims, hạn mới) vào response header **`X-New-Access-Token`**; client thấy header thì tự thay token. Stateless hoàn toàn — không có refresh-token store.
  3. Token sai/hết hạn → không set context → entry point trả 401.
- **`SecurityFilterChain`** bean (`@ConditionalOnMissingBean` — app override được): CSRF off (stateless API), session `STATELESS`, whitelist permitAll, còn lại `authenticated()`.
- **`RestAuthenticationEntryPoint`** (401) + **`RestAccessDeniedHandler`** (403) — body là **`ErrorResponse`** JSON đúng envelope hiện có của project.
- **`PasswordEncoder`** BCrypt (`@ConditionalOnMissingBean`).
- **Port `SecurityUserService`** (interface của module):
  ```java
  SecurityUser loadByUsername(String username);
  // SecurityUser: Long uid, String username, String passwordHash,
  //               boolean enabled, Set<String> roles, Set<String> permissions
  ```
- **`AuthController`** — `POST /auth/login` `{username, password}` → `{accessToken, tokenType: "Bearer", expiresIn}`; sai credentials → 401 `ErrorResponse`. `@ConditionalOnBean(SecurityUserService.class)` — app không cung cấp port thì login tắt, module chạy chế độ validate-only.

**Hợp đồng sử dụng cho app mới (3 bước):** (1) khai dependency `jp.co.htkk:security`; (2) set `app.security.jwt.secret`; (3) cung cấp 1 bean `SecurityUserService`. Xong — mọi endpoint được bảo vệ mặc định.

## 4. RBAC schema (PostgreSQL; 4 bảng mới + sửa `users`)

Tất cả bảng theo convention hiện có (audit columns + `del_flag SMALLINT DEFAULT 0`):

- `users` **+ cột** `password VARCHAR(255) NOT NULL`
- `roles` (`role_id` IDENTITY PK, `role_code VARCHAR(50) UNIQUE`, `role_name VARCHAR(255)`, audit, del_flag)
- `permissions` (`permission_id` IDENTITY PK, `permission_code VARCHAR(100) UNIQUE`, `permission_name VARCHAR(255)`, audit, del_flag)
- `user_roles` (`user_id`, `role_id`, PK ghép)
- `role_permissions` (`role_id`, `permission_id`, PK ghép)

**Stateless:** login nạp roles + permissions **một lần** và nhúng vào JWT claims → các request sau không chạm DB để authorize.

**Seed (migration + docker init + test data):** permissions `USER_READ`, `USER_WRITE`; role `ADMIN` (cả hai perms), role `USER` (`USER_READ`); user `admin` (password BCrypt) gán role `ADMIN`; user thường gán role `USER` (cho test 403).

Cập nhật đồng bộ 3 nơi: script `mybatis-schema-migration`, `web/api/src/test/resources/schema.sql` + `data.sql`, `docker/postgres-init/01-create-users.sql`.

## 5. Tích hợp vào app hiện tại (web/api làm mẫu)

- `web/api/pom.xml` khai dependency `security`; set `app.security.jwt.secret` trong `application.yml` (qua env `${JWT_SECRET}`; docker-compose set sẵn giá trị dev).
- **Xóa `AuthorizationInterceptor`** + đăng ký của nó trong `WebMvcConfiguration` (Spring Security filter chain thay thế).
- **Adapter** `SecurityUserServiceImpl` (đặt ở `web/api` để dependency security chỉ nằm ở app web): query `users` + `user_roles`/`roles` + `role_permissions`/`permissions` qua mapper viết tay mới trong `persistence` (pattern giống `UserMapper`).
- **`LoginInfo`** (framework) thành ThreadLocal holder thật: `uid`, `username`, `fromContext()/set()/clear()` — framework **không** phụ thuộc spring-security; security module là bên ghi.
- **`AuditInterceptor`**: `getUid()` đọc `LoginInfo.fromContext()` → `created_by/updated_by` = **uid thật** từ token (hết kỷ nguyên uid = 0).
- **Demo phân quyền** trên User CRUD: `GET /admin/users**` cần authority `USER_READ`; `POST /admin/users` cần `USER_WRITE` (`@PreAuthorize`).
- **Springdoc**: thêm bearer `SecurityScheme` → Swagger UI có nút Authorize; cập nhật `UserCreateRequest` flow không đổi.
- **Actuator**: `management.endpoint.health.show-details: when-authorized` (đang `always`).
- `endpoint.yml` giữ nguyên — login path do module cố định tại `/auth/login`, không cấu hình qua endpoint.yml.

## 6. Error handling

- 401 (thiếu/sai/hết hạn token, sai credentials) và 403 (thiếu quyền) đều trả `ErrorResponse` JSON envelope hiện có — nhất quán với `ExceptionControllerAdvice`.
- `BadCredentialsException` ở login map về 401, không lộ chi tiết user-exists.
- Token renew không áp dụng cho token đã hết hạn (hết hạn = 401, phải login lại).

## 7. Testing (H2 MODE=PostgreSQL, không mock)

1. `POST /auth/login` đúng (admin) → 200, có `accessToken`, `expiresIn`.
2. Login sai password → 401 `ErrorResponse`.
3. `GET /admin/users` không token → 401; token rác → 401.
4. Token role `USER` gọi `POST /admin/users` → 403; `GET` → 200.
5. Token role `ADMIN` `POST` → 200, và **`created_by` trong DB = uid của admin** (audit thật, hết uid=0).
6. **Renew:** phát token hạn ngắn (< renew-window, qua `JwtTokenService` trực tiếp trong test) → gọi API → response có header `X-New-Access-Token`, token mới hợp lệ và dùng được.
7. Whitelist: swagger/api-docs/actuator-health truy cập không cần token.
8. `BatchContextLoadsTest` vẫn xanh (batch không khai security).

## 8. Verification (end-to-end)

1. `mvn clean install -pl -mybatis-generator,-mybatis-schema-migration` (Java 21) xanh — module `security` mới nằm trong reactor.
2. Toàn bộ integration test §7 xanh trên H2-PG.
3. `docker compose up --build` → login admin qua curl lấy token → gọi `GET/POST /admin/users` bằng token → đúng 200/401/403; Swagger UI có nút Authorize và gọi được API sau khi Authorize.

## 9. Ngoài scope

- Refresh-token store / thu hồi token (renew qua header là đủ cho base; thêm sau nếu dự án cần).
- Trang quản trị role/permission (CRUD RBAC) — chỉ seed dữ liệu; API quản trị RBAC để dự án con tự thêm.
- OAuth2/social login, multi-tenant, rate-limiting.
- Sub-project C (cleanup) — vẫn để sau.

## 10. Bước tiếp theo

1. Chủ dự án review spec này.
2. Chuyển sang **writing-plans** lập implementation plan chi tiết.
3. Sau B → sub-project **C (cleanup)**.

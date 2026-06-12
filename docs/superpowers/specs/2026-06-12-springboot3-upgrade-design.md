# Design Spec — Nâng Spring Boot 3.3.x + Java 21

> Sub-project **A** trong lộ trình **A → B → C**. Ngày: 2026-06-12.

## 1. Bối cảnh (Context)

`springboot-multi-module` (`jp.co.htkk`, point-management-sys) là **base template** dùng lại để khởi tạo các dự án Java mới. Hiện tại chạy Spring Boot 2.7.4 / Java 11 / MyBatis, kiến trúc multi-module giao tiếp qua interface (11 module: framework, dto, entity, persistence, business-interface, business-implementation, web/api, batch, mybatis-generator, mybatis-schema-migration, + các parent pom).

Chủ dự án có 3 mục tiêu phụ thuộc nhau, làm tuần tự — mỗi phần một spec riêng:

- **A (spec này):** Nâng Spring Boot 2.x → 3.3.x + Java 21. Nền tảng cho B.
- **B (sau):** Module security tái sử dụng, xây trên Spring Security 6.
- **C (sau):** Cải thiện chất lượng code không-critical.

**Vì sao làm A trước:** Spring Security 6, `jakarta`, Java 17+ đều đi kèm Boot 3. Nếu xây security trên Boot 2 rồi mới lên Boot 3 sẽ phải viết lại security config (`WebSecurityConfigurerAdapter → SecurityFilterChain`). Làm A trước → xây B đúng một lần.

Khảo sát xác nhận security hiện tại chỉ là **khung rỗng**: `AuthorizationInterceptor` luôn `return true`, `LoginInfo.fromContext()` trả `null`, không có Spring Security/JWT, User entity không có cột password. Do đó security là phạm vi của B, không phải A.

## 2. Quyết định đã chốt

| Hạng mục | Quyết định |
|---|---|
| Phiên bản đích | Spring Boot **3.3.x** + Java **21** (LTS) |
| Sửa bug | Gộp các bug chặn build/runtime vào A |
| Phạm vi module | **Tất cả 11 module** (gồm batch + mybatis tooling) |
| Verify | **Integration Test** thật (Controller → Service → MyBatis → DB), **không mock**, DB **H2** tự tạo |
| Cách migrate | **OpenRewrite** (recipe `UpgradeSpringBoot_3_3`) + dọn tay |

## 3. Phạm vi

**Trong scope:**
1. Nâng version/dependency 11 module lên Boot 3.3.x + Java 21.
2. `javax` → `jakarta` (66 files, 121 imports).
3. Migrate springdoc v1 → v2.
4. Sửa 3 bug nghiêm trọng (§5).
5. Bộ Integration Test trên H2.

**Ngoài scope (để B/C):** module security; `LoginInfo` / `AuthorizationInterceptor` / `AuditInterceptor uid=0` (thuộc security → B); cải thiện không-critical (chuẩn hóa response envelope, `AbstractBaseService` rỗng, đổi tên `UserController.getMonthlyPoint`, README → C).

## 4. Nâng version & dependency

File: root `pom.xml`, `framework/pom.xml`, `dto/pom.xml`, `persistence/pom.xml`, `web/api/pom.xml`, `batch/pom.xml`, `mybatis-generator/pom.xml`, `mybatis-schema-migration/pom.xml`.

- `java.version` 11 → **21**; maven-compiler `<release>21</release>`.
- `spring-boot-starter-parent` 2.7.4 → **3.3.x** (patch ổn định mới nhất).
- `mybatis-spring-boot-starter` 2.2.2 → **3.0.x**.
- `pagehelper-spring-boot-starter` 1.4.5 → bản tương thích Boot 3 (**2.1.x**).
- `springdoc-openapi-ui:1.6.x` → **`springdoc-openapi-starter-webmvc-ui:2.6.x`** (đổi coordinate ở framework & dto; gỡ override 1.6.4 trong framework).
- MySQL driver `mysql:mysql-connector-java:8.0.20` → **`com.mysql:mysql-connector-j`** (Boot 3 BOM quản version).
- `lombok` 1.18.24 → **1.18.34** (bắt buộc cho Java 21).
- Bump nhẹ: guava 31.1+, gson 2.10.1, opencsv 5.7+, commons-io/text/lang3; `mybatis-generator-core` 1.4.1 → 1.4.2.

## 5. `javax` → `jakarta` (OpenRewrite) + sửa bug

**OpenRewrite:** thêm tạm `rewrite-maven-plugin` + recipe `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3`, chạy `mvn rewrite:run` → tự đổi `javax.validation`/`javax.servlet`/`javax.sql` → `jakarta.*` và property deprecated. Vùng lớn nhất: 27 custom validator trong `framework/.../validation/`. Sau đó **gỡ plugin**, review diff, build bottom-up.

**Sửa 3 bug nghiêm trọng (gộp vào A):**
1. `LoggingAspect` (web/api `aspect/`, dòng pointcut): `ko.alliex.energy.api.controller.*` → `jp.co.htkk.api.controller.*` (đang khiến aspect không log gì).
2. `MethodArgumentNotValidExceptionHandler` (framework `exception/handler/impl/`): bỏ `@Autowired MessageService` (class không do Spring quản lý → NPE khi validate lỗi) → truyền qua **constructor** từ `ExceptionConfiguration` factory.
3. Bỏ default `username: root` / `password: 123456` hardcode trong `web/api` & `batch` `application.yml` → bắt buộc env var, không default yếu.

## 6. springdoc v1 → v2

Rà `SpringdocConfig.java` và block `springdoc.*` trong application.yml; cập nhật đường dẫn Swagger UI nếu đổi; đảm bảo `excludePathPatterns(SWAGGER)` trong `WebMvcConfiguration` khớp path mới của springdoc v2.

API deprecated: khảo sát xác nhận **không** dùng `WebSecurityConfigurerAdapter`, `WebMvcConfigurerAdapter`, `antMatchers`, `@EnableGlobalMethodSecurity`; `WebMvcConfiguration` đã `implements WebMvcConfigurer` (đúng chuẩn Boot 3) → rủi ro refactor thấp.

## 7. Bộ Integration Test

- Vị trí: `web/api/src/test/...` (và `batch/src/test/...` nếu cần smoke batch).
- Kiểu: **không mock** — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `MockMvc`/`TestRestTemplate`, gọi HTTP thật xuyên suốt Controller → Service → MyBatis mapper → DB cho luồng **Dashboard** và **User**.
- DB: **H2 in-memory `MODE=MySQL`**, profile `application-test.yml` riêng.
- Schema: `src/test/resources/schema.sql` lấy DDL từ module **mybatis-schema-migration** (đồng bộ schema thật) + `data.sql` seed; Spring tự chạy (`spring.sql.init`).
- **Dự phòng rủi ro H2:** nếu custom SQL trong `persistence/.../dao/custom/` dùng hàm đặc thù MySQL (`DATE_FORMAT`, `GROUP_CONCAT`…) không chạy trên H2 → chuyển **chỉ class test bị ảnh hưởng** sang **Testcontainers MySQL**, phần còn lại giữ H2.

## 8. Verification (end-to-end)

1. `mvn clean package` **toàn bộ 11 module** pass trên **Java 21** (không loại trừ module nào).
2. Toàn bộ **Integration Test xanh** (H2; nếu có class Testcontainers thì cần Docker).
3. Chạy `web/api` (port 9000, context `/api/v1/`): **Swagger UI load được**, smoke endpoint Dashboard/User qua HTTP đúng.
4. Build & chạy thử **batch** để chắc chắn batch cũng lên Boot 3 không lỗi context.

## 9. Bước tiếp theo

1. Chủ dự án review spec này.
2. Chuyển sang **writing-plans** để lập implementation plan chi tiết cho A.
3. Sau A → brainstorm **B (module security)** → **C (cleanup)**.

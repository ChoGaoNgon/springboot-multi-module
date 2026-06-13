# Spec — Application module aggregator (`application/`)

Tạo Maven aggregator POM `application/` để gom các module tầng application (hiện tại: `web/api` và `batch`; tương lai: `admin-api`, `notifier`, v.v.). Mục tiêu: shared deps một chỗ, thêm app mới chỉ cần tạo `application/<tên>/`, không đụng root.

## Mục tiêu

1. Thay `web/` parent POM + `batch/` standalone bằng 1 parent chung `application/`.
2. Đẩy deps thật sự chung (chỉ những cái CẢ HAI app dùng) lên parent; deps riêng giữ ở sub.
3. Giữ nguyên artifactId (`api`, `batch`), tên JAR, Spring profile, env var, docker-compose — không phá downstream.
4. Cập nhật Dockerfile, CLAUDE.md, README theo path mới.

## Không nằm trong scope

- Không gộp 2 Spring Boot main class. Mỗi sub module vẫn build ra 1 fat JAR riêng và chạy độc lập.
- Không refactor package Java (`jp.co.htkk.api.*`, `jp.co.htkk.batch.*` giữ nguyên).
- Không đổi tên artifactId.
- Không tạo app mới trong scope này.

## Layout sau refactor

```
.
├── pom.xml                         # root: <modules> thay web+batch → application
├── framework/                      # ← không đổi
├── security/
├── dto/
├── entity/
├── persistence/
├── business/                       # bao gồm business-interface + business-implementation
├── mybatis-generator/
├── mybatis-schema-migration/
├── Dockerfile                      # ← path cập nhật web/ → application/
└── application/                    # ★ MỚI
    ├── pom.xml                     # packaging=pom, parent của 2 sub
    ├── api/                        # ← move từ web/api/
    │   ├── pom.xml
    │   └── src/                    # giữ nguyên Java code
    └── batch/                      # ← move từ batch/
        ├── pom.xml
        └── src/
```

`web/` (parent POM) và `batch/` (standalone) cũ biến mất.

## Module mapping

| Trước | Sau |
|---|---|
| `web/` (aggregator POM) | XÓA — nội dung gộp vào `application/pom.xml` |
| `web/api/` (artifactId=`api`) | `application/api/` (artifactId vẫn `api`) |
| `batch/` (artifactId=`batch`) | `application/batch/` (artifactId vẫn `batch`) |
| — | `application/` (artifactId=`application`, packaging=pom) MỚI |

## Phân chia dependency

### `application/pom.xml` (parent — packaging=pom)
Chỉ gom **deps CẢ HAI sub đều cần**:
- `jp.co.htkk:business-implementation` — cả api và batch đều gọi business
- Property `<appName>${project.artifactId}-${project.version}</appName>`
- `<build><finalName>${appName}</finalName></build>` + `spring-boot-maven-plugin`
- `<pluginRepositories>` (spring snapshots / milestones — hiện có trong `web/pom.xml`)
- `<modules>api, batch</modules>`

### `application/api/pom.xml`
Move từ `web/api/pom.xml`. Đổi `<parent>` từ `web` → `application`. Giữ deps riêng:
- `spring-boot-starter-actuator`
- `spring-boot-starter-aop` (**push DOWN** từ `web/pom.xml` cũ vì batch không dùng)
- `jp.co.htkk:security`
- `com.h2database:h2` (test scope)

### `application/batch/pom.xml`
Move từ `batch/pom.xml`. Đổi `<parent>` từ `point-management-sys` → `application`. Bỏ deps đã chung:
- BỎ: `business-implementation` (lên parent), `spring-boot-maven-plugin` (lên parent), `lombok` (redundant — root pom đã có)
- GIỮ: `spring-boot-starter-web` + tomcat excludes, `spring-boot-devtools`, `spring-boot-configuration-processor`, `com.fasterxml.jackson.core:jackson-databind`, `com.monitorjbl:xlsx-streamer`, `spring-boot-starter-test`, `com.h2database:h2`

**Nguyên tắc:** đẩy lên parent chỉ khi mọi sub đều cần. Tránh tình trạng `web/pom.xml` cũ tự động áp `spring-boot-starter-aop` cho mọi child kể cả không dùng.

## Thay đổi file (impact list)

| File | Hành động |
|---|---|
| `pom.xml` (root) | Bỏ `<module>web</module>` + `<module>batch</module>`. Thêm `<module>application</module>`. |
| `web/pom.xml` | XÓA. |
| `web/api/pom.xml` | MOVE → `application/api/pom.xml`. Đổi `<parent>.<artifactId>` từ `web` → `application`. Thêm dep `spring-boot-starter-aop`. |
| `web/api/src/**` | MOVE → `application/api/src/**` (giữ nguyên code). |
| `batch/pom.xml` | MOVE → `application/batch/pom.xml`. Đổi `<parent>.<artifactId>` từ `point-management-sys` → `application`. Bỏ deps đã lên parent. |
| `batch/src/**` | MOVE → `application/batch/src/**`. |
| `application/pom.xml` | TẠO MỚI (nội dung kế thừa từ `web/pom.xml` cũ + `<modules>api,batch</modules>` + property `appName`). |
| `Dockerfile` | `COPY web/pom.xml ...` + `COPY web/api/pom.xml ...` + `COPY batch/pom.xml ...` → `COPY application/pom.xml application/pom.xml` + `COPY application/api/pom.xml application/api/pom.xml` + `COPY application/batch/pom.xml application/batch/pom.xml`. `mvn -am -pl web/api` → `mvn -am -pl application/api`. `COPY web web` → `COPY application/api application/api`. JAR path `web/api/target/api-0.0.1-SNAPSHOT.jar` → `application/api/target/api-0.0.1-SNAPSHOT.jar`. |
| `CLAUDE.md` | Cập nhật bảng module + lệnh build (`-pl web/api` → `-pl application/api`). Bảng thêm hàng `application` (aggregator). |
| `README.md` | Tương tự — section "Architecture — multi-module" cây thư mục + module table + section "How to build". |

## Compat / backwards-compat

- ArtifactId giữ nguyên (`api`, `batch`) → bất kỳ chỗ nào `<dependency><artifactId>api</artifactId>` không phá.
- Tên fat JAR giữ nguyên (`api-0.0.1-SNAPSHOT.jar`, `batch-0.0.1-SNAPSHOT.jar`) vì `<appName>${project.artifactId}-${project.version}</appName>` được resolve theo child context.
- Spring profile (`development`/`staging`/`production`), env vars, docker-compose files, `application.yml`, logback config: không đổi.
- Migration tooling, mybatis-generator: không đổi.

## Verification (sau implementation)

- [ ] `mvn clean install -pl -mybatis-generator,-mybatis-schema-migration` BUILD SUCCESS (cần JDK 21).
- [ ] `mvn test -pl application/api` chạy được integration test trên H2 (giống `web/api` cũ).
- [ ] `docker compose up --build -d` (dev compose) → API up, health endpoint trả 200.
- [ ] `application/api/target/api-0.0.1-SNAPSHOT.jar` tồn tại sau build.
- [ ] `application/batch/target/batch-0.0.1-SNAPSHOT.jar` tồn tại sau build.
- [ ] `grep -rE 'web/api|web/pom' Dockerfile CLAUDE.md README.md` không còn match.

## Rủi ro & xử lý

| Rủi ro | Xử lý |
|---|---|
| Quên path trong Dockerfile multi-stage build → build fail trên VPS | Verify item ở trên + chạy thử `docker build .` local trước khi push |
| `web/api/pom.xml` cũ inherit `spring-boot-starter-aop` từ `web/`. Nếu quên thêm vào `application/api/pom.xml` → ClassNotFound `@Aspect`. | Item phân chia deps đã ghi rõ: AOP push DOWN. Verify khi test integration. |
| IntelliJ cache module map cũ → import lỗi | `File > Invalidate Caches` sau khi pull, hoặc reimport Maven. |
| Branch cũ (chưa merge) đụng `web/api/` path | Sau khi merge spec này, các branch cũ rebase sẽ có conflict path. Document trong PR. |

## Future modules

Sau khi xong: thêm app `notifier` chẳng hạn:
1. Tạo `application/notifier/pom.xml` (parent = `application`).
2. Thêm `<module>notifier</module>` vào `application/pom.xml`.
3. Code Spring Boot main class + entry point trong `application/notifier/src/`.
4. KHÔNG đụng root `pom.xml`, KHÔNG đụng module khác.

Đây là lợi ích cốt lõi của refactor này.

# point-management-sys — Spring Boot 3 multi-module base

Base template (groupId `jp.co.htkk`) để khởi tạo dự án Java mới: **Spring Boot 3.3.5 / Java 21**, kiến trúc **multi-module** giao tiếp qua interface, **MyBatis + PostgreSQL**, **module security tái sử dụng** (JWT + RBAC), test tích hợp trên H2, và đóng gói **Docker multi-stage** cho dev/staging/production.

## Technology stack

| Category | Detail | Version |
|---|---|---|
| Language | `JDK` | **21 (LTS)** |
| Build tool | `apache-maven` | 3.9 |
| Framework | `Spring Boot` | 3.3.5 |
| Security | `Spring Security 6` + `jjwt` (JWT HS256) | jjwt 0.12.6 |
| Persistence | `mybatis-spring-boot-starter` | 3.0.3 |
| Paging | `pagehelper-spring-boot-starter` (`postgresql` dialect) | 2.1.0 |
| Database (prod) | `PostgreSQL` | 16 |
| Database (test) | `H2` in-memory, `MODE=PostgreSQL` | — |
| Web server | `Apache Tomcat` (embedded) | 10.1 |
| API docs | `springdoc-openapi-starter-webmvc-ui` | 2.6.0 |
| Logging | `Logback` | — |
| Utilities | `Lombok` | 1.18.34 |
| Code gen | `mybatis-generator-maven-plugin` | 1.4.2 |
| Schema migration | `mybatis-migrations-maven-plugin` | 1.1.3 |
| Packaging | `Docker` multi-stage (`eclipse-temurin:21-jre`, non-root) | — |

## Architecture — multi-module

Tách module giúp giảm trùng lặp, tái sử dụng (vd `security`, `framework`), build/test từng phần và dễ bảo trì. Thứ tự build và chiều phụ thuộc:

```
framework → security → dto → entity → persistence → business → web/api → batch
                                          (+ mybatis-generator, mybatis-schema-migration: tooling)
```

```
.
├── pom.xml                         # aggregator + dependencyManagement (version tập trung)
├── framework/                      # core dùng chung, không phụ thuộc module nào
│   └── jp/co/htkk/framework/{component,constant,converter,csv,enums,exception,
│                              httpclient,mail,security/model(LoginInfo),util,validation}
├── security/                       # MODULE SECURITY tái sử dụng (auto-config)
│   └── jp/co/htkk/security/
│       ├── config/                 # SecurityModuleAutoConfiguration, SecurityModuleProperties
│       ├── jwt/                     # JwtTokenService, JwtPrincipal
│       ├── port/                    # SecurityUser, SecurityUserService (app tự cài đặt)
│       └── web/                     # AuthController, JwtAuthenticationFilter, Rest{Auth,AccessDenied}Handler, dto
├── entity/                         # Entity MyBatis (User)
├── dto/                            # common: REQUEST/DXO/PRM/RST/RESPONSE + Envelope/Meta; admin/user/*
├── persistence/                    # DAO MyBatis: UserMapper(.java/.xml), UserAuthMapper(.java/.xml)
├── business/
│   ├── business-interface/         # service interface (admin/UserService, AbstractBaseService)
│   └── business-implementation/    # impl (admin/UserServiceImpl) + unit/integration tests
├── web/                            # ── module chính ──
│   └── api/                        # PointManagementSysApplication, controller/admin/UserController,
│                                   #   config(MyBatisConfig, WebMvcConfiguration, SpringdocConfig),
│                                   #   exception/ExceptionControllerAdvice, security/SecurityUserServiceImpl
├── batch/                          # batch job độc lập (không dùng security)
├── mybatis-generator/              # tooling: generate entity/dao
├── mybatis-schema-migration/       # tooling: DDL migration + migrate.sh
├── docker-compose.yaml             # DEV: postgres:16 + api + seed RBAC
├── docker-compose.staging.yaml     # STAGING: chỉ api, DB ngoài
├── docker-compose.production.yaml  # PRODUCTION: chỉ api, DB ngoài
├── Dockerfile                      # multi-stage (build maven → runtime jre slim, non-root)
└── .env.example                    # mẫu biến môi trường (không chứa giá trị thật)
```

| Module | Mô tả |
|---|---|
| **framework** | Core: error envelope (`ErrorResponse`/`ErrorCode`), `@ControllerAdvice` mặc định, custom validators (`jakarta.*`), i18n (`MessageService`), `LoginInfo` (ThreadLocal principal), util. |
| **security** | JWT HS256 + RBAC tự-cấu-hình. Deny-all-except-whitelist, login `/auth/login`, 401/403 theo error envelope, tự gia hạn token. App khác chỉ **khai dependency + set secret + cung cấp 1 bean `SecurityUserService`**. |
| **entity** | Entity MyBatis. |
| **dto** | DTO + luồng dữ liệu giữa các tầng. |
| **persistence** | Repository layer (MyBatis mapper). |
| **business** | Business logic (interface + implementation tách riêng). |
| **web/api** | App chính: REST controller, config, exception advice, adapter security. |
| **batch** | Scheduled batch job. |
| **mybatis-generator / mybatis-schema-migration** | Công cụ generate code / quản lý DDL. |

## DTO flow

`REQUEST.toDxo()` → `DXO.toPrm()` → service xử lý → `RST` → `RESPONSE` (bọc trong `Envelope`/`Meta`). Validate qua `AbstractBaseController.bindingResultWithValidate(...)`.

## How to build

Cần **JDK 21**. Loại 2 module tooling (cần DB sống để build):

```bash
mvn clean install -pl -mybatis-generator,-mybatis-schema-migration
```

Chỉ test web/api (integration test trên H2 PostgreSQL-mode):
```bash
mvn test -pl web/api
```

## How to run — local (Docker Compose)

Dev compose chạy `postgres:16` + `api`, tự tạo bảng RBAC + seed user qua `docker/postgres-init/`:

```bash
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij docker compose up --build -d
# health (whitelist, không cần token):
curl http://localhost:9000/api/v1/actuator/health
# tear down:
docker compose down -v
```

Seed mặc định: `admin` / `admin123` (role ADMIN: `USER_READ`+`USER_WRITE`), `normal` / `user123` (role USER: `USER_READ`).

## Authentication & RBAC

- **Login** lấy access token (JWT HS256, TTL 30m):
  ```bash
  curl -X POST http://localhost:9000/api/v1/auth/login \
    -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'
  # → {"accessToken":"...","tokenType":"Bearer","expiresIn":1800}
  ```
- Gọi API bảo vệ: thêm header `Authorization: Bearer <token>`. Không có token → 401; thiếu quyền → 403 (theo error envelope).
- Phân quyền method-level: `@PreAuthorize("hasAuthority('USER_WRITE')")` trên controller.
- **Tự gia hạn**: khi token còn ≤ 3 phút, response trả token mới qua header `X-New-Access-Token` — client thay token đang lưu, phiên "trượt" liên tục mà không cần refresh-token riêng.
- Dùng cho app khác: khai dependency `jp.co.htkk:security`, set `app.security.jwt.secret`, cung cấp 1 bean `SecurityUserService`.

## Database schema & migration

- **Dev**: schema + seed tạo tự động bởi `docker/postgres-init/01-create-users.sql` (chỉ chạy **lần đầu** khi volume rỗng — không phải công cụ migration).
- **Staging / Production**: dùng `mybatis-schema-migration` qua wrapper `migrate.sh` (inject connection từ biến môi trường). 2 cách chạy:

  **A. Máy có sẵn JDK 21 + Maven** (vd máy dev):
  ```bash
  export POSTGRES_HOST=db.internal POSTGRES_PORT=5432 POSTGRES_DB=app \
         POSTGRES_USER=app_user POSTGRES_PASSWORD='********'
  ./mybatis-schema-migration/migrate.sh production status      # xem trạng thái
  ./mybatis-schema-migration/migrate.sh production up          # áp dụng
  ./mybatis-schema-migration/migrate.sh production down -Dmigration.down.steps=1   # rollback 1 bước
  ```

  **B. Máy chỉ có Docker** (vd VPS production — không cài JDK/Maven): chạy migrate.sh trong container Maven tạm thời, mount code vào:
  ```bash
  set -a; . ./.env.production; set +a   # load POSTGRES_* + JWT_SECRET vào shell
  docker run --rm -v "$PWD":/app -w /app \
    -e POSTGRES_HOST -e POSTGRES_PORT -e POSTGRES_DB -e POSTGRES_USER -e POSTGRES_PASSWORD \
    maven:3.9-eclipse-temurin-21 \
    bash mybatis-schema-migration/migrate.sh production up
  ```
  (Để giảm thời gian build cho lần sau, mount thêm cache: `-v "$HOME/.m2":/root/.m2`.)

## How to run — Staging / Production

Staging/production dùng **PostgreSQL bên ngoài** (managed như Supabase / RDS / Neon, hoặc DB trên VPS khác — compose KHÔNG dựng DB). Image build tại chỗ trên VPS từ `Dockerfile` multi-stage (non-root). Secret inject lúc run qua `--env-file`. **VPS chỉ cần Docker + Docker Compose + git** — KHÔNG cần JDK/Maven (migration chạy trong container Maven tạm theo cách B ở mục trên).

Biến **bắt buộc**: `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET` (≥ 32 bytes). Thiếu bất kỳ biến nào → compose dừng ngay. Xem `.env.example` cho danh sách đầy đủ.

### Setup VPS lần đầu

```bash
# 1) Cài Docker + Compose (Ubuntu/Debian):
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER && newgrp docker

# 2) Clone repo:
git clone <repo-url> /opt/app && cd /opt/app

# 3) Tạo file env (KHÔNG commit, đã gitignore):
cp .env.example .env.production
# → Sửa POSTGRES_*, JWT_SECRET (random ≥32 bytes: openssl rand -base64 48)

# 4) Firewall — nếu TLS terminate ở dịch vụ ngoài (vd Cloudflare):
#    Chỉ allow port 9000 từ dải IP của provider, KHÔNG mở public —
#    không là ai biết IP VPS sẽ bypass được TLS proxy.
#    Ví dụ với ufw + Cloudflare:
sudo ufw default deny incoming && sudo ufw allow ssh
for ip in $(curl -s https://www.cloudflare.com/ips-v4); do sudo ufw allow from $ip to any port 9000; done
sudo ufw enable

# 5) Áp schema lần đầu (xem mục migration cách B):
set -a; . ./.env.production; set +a
docker run --rm -v "$PWD":/app -w /app \
  -e POSTGRES_HOST -e POSTGRES_PORT -e POSTGRES_DB -e POSTGRES_USER -e POSTGRES_PASSWORD \
  maven:3.9-eclipse-temurin-21 \
  bash mybatis-schema-migration/migrate.sh production up

# 6) Seed data ban đầu (admin user + roles + permissions) — chạy 1 lần:
#    Chuẩn bị file seed-production.sql (KHÔNG commit) chứa INSERT roles/permissions/admin.
docker run --rm -i -v "$PWD":/app -w /app postgres:16 \
  psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@$POSTGRES_HOST:${POSTGRES_PORT:-5432}/$POSTGRES_DB" \
  < seed-production.sql

# 7) Up API:
docker compose --env-file .env.production -f docker-compose.production.yaml up -d --build

# 8) Verify (từ trên VPS):
curl -fsS http://localhost:9000/api/v1/actuator/health
```

### Deploy code mới (lặp lại mỗi lần release)

```bash
cd /opt/app
git pull

# Kiểm tra có migration mới chưa, nếu có thì áp:
set -a; . ./.env.production; set +a
docker run --rm -v "$PWD":/app -w /app \
  -e POSTGRES_HOST -e POSTGRES_PORT -e POSTGRES_DB -e POSTGRES_USER -e POSTGRES_PASSWORD \
  maven:3.9-eclipse-temurin-21 \
  bash mybatis-schema-migration/migrate.sh production status
# nếu thấy "pending" → đổi `status` thành `up` và chạy lại

# Rebuild image + restart container (zero-downtime: compose tự graceful restart):
docker compose --env-file .env.production -f docker-compose.production.yaml up -d --build

# Verify:
curl -fsS http://localhost:9000/api/v1/actuator/health
```

### Staging

Y hệt production, đổi `production` → `staging` (`.env.staging`, `docker-compose.staging.yaml`, `migrate.sh staging up`).

## Configuration (env vars)

| Biến | Bắt buộc | Mô tả |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | — | `development` / `staging` / `production` |
| `POSTGRES_HOST/PORT/DB/USER/PASSWORD` | ✅ (staging/prod) | Kết nối PostgreSQL |
| `JWT_SECRET` | ✅ | Khóa HS256, ≥ 32 bytes |
| `APP_PORT` | — | Port publish (mặc định 9000) |
| `LOG_PATH` | — | Thư mục log (mặc định `/var/log/app`) |
| `JAVA_TOOL_OPTIONS` | — | Mặc định `-XX:MaxRAMPercentage=75` |
| `CPU_LIMIT` / `MEM_LIMIT` | — | Giới hạn tài nguyên container |

## API documentation (Swagger UI)

```
http://localhost:9000/api/v1/swagger-ui/index.html
```
Có nút **Authorize** (bearer JWT) để gọi thử endpoint đã bảo vệ.

## Deploy Batch module

Xem [batch/README.md](batch/README.md).

## Notes

- Header `Accept-Language`: `en`, `ja` (hoặc bỏ trống) cho i18n thông báo lỗi/validate.
- Actuator: `GET /api/v1/actuator/health` (whitelist). Chi tiết health chỉ hiện khi đã xác thực (`show-details: when-authorized`).

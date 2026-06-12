# Docker Compose cho Staging & Production + Multi-stage Dockerfile — Design Spec

**Ngày:** 2026-06-13
**Nhánh dự kiến:** `feature/staging-prod-compose`

## Bối cảnh

Dự án `springboot-multi-module` hiện chỉ có một file [docker-compose.yaml](../../../docker-compose.yaml) dùng cho **dev**:
- Build image tại chỗ từ [Dockerfile](../../../Dockerfile) (hiện là "fat image": cả môi trường Maven, chạy `java -jar` ngay trên image build).
- Chạy `postgres:16` ngay trong compose, password hardcode `123456`.
- `JWT_SECRET` có default dev.
- Mount `docker/postgres-init` → init script chạy lần đầu khi volume rỗng (tạo bảng RBAC + seed). Chỉ hợp cho dev.

App đã hoàn toàn điều khiển qua biến môi trường: `SPRING_PROFILES_ACTIVE`, `POSTGRES_HOST/PORT/DB/USER/PASSWORD`, `JWT_SECRET`, `LOG_PATH`, `PORT`. Profile `staging`/`production` đã được khai báo trong [web/api application.yml](../../../web/api/src/main/resources/application.yml) (block thứ hai set `logging.file.path`).

## Mục tiêu

Cung cấp file compose **riêng cho staging và production**, phù hợp vận hành thật, và làm Dockerfile thành **multi-stage** (runtime gọn, non-root).

## Quyết định đã chốt (qua brainstorming)

| Hạng mục | Quyết định |
|---|---|
| Database staging & prod | **DB ngoài/managed** cho cả hai. Compose chỉ định nghĩa service `api`, kết nối qua env. KHÔNG có service Postgres, volume, hay init script. |
| Quản lý secret | **Inject lúc run** (shell/CI hoặc `--env-file`). Compose chỉ tham chiếu `${VAR}`; secret bắt buộc dùng `:?` → thiếu là `up` fail ngay. Không commit giá trị thật. |
| Nguồn image | **Build tại chỗ** từ `Dockerfile` (`build:`), giống dev. |
| Cấu trúc file | **2 file độc lập** `docker-compose.staging.yaml` + `docker-compose.production.yaml`. Giữ `docker-compose.yaml` làm dev. |
| Dockerfile | **Multi-stage** (build → runtime JRE slim, non-root). Trong scope task này. |
| Migration env-parameterize | **Follow-up riêng**, ngoài scope. |

## Phạm vi (Scope)

**Trong scope:**
1. Tạo `docker-compose.staging.yaml` và `docker-compose.production.yaml` (chỉ service `api`).
2. Chuyển `Dockerfile` sang multi-stage (build + runtime JRE slim, non-root, có `curl` cho healthcheck).
3. Thêm `.env.example` (tài liệu hoá biến cần thiết, không có giá trị thật).
4. Cập nhật `.gitignore` để chặn `.env`, `.env.staging`, `.env.production`.
5. Cập nhật README: cách chạy staging/prod, biến bắt buộc, nhắc migration là tiền đề.

**Ngoài scope (follow-up):**
- Tham số hoá file env của `mybatis-schema-migration` (`development/staging/production.properties` đang hardcode `localhost` + password).
- CI/CD pipeline, registry, reverse proxy/TLS, orchestrator (k8s/swarm).
- Một-shot migration service trong compose.

## File structure

| File | Hành động | Trách nhiệm |
|---|---|---|
| `docker-compose.staging.yaml` | Tạo | Chạy `api` trên profile `staging`, DB ngoài |
| `docker-compose.production.yaml` | Tạo | Chạy `api` trên profile `production`, DB ngoài |
| `Dockerfile` | Sửa | Multi-stage: build (maven) → runtime (`eclipse-temurin:21-jre`, non-root, `curl`) |
| `.env.example` | Tạo | Liệt kê biến cần inject (không giá trị thật) |
| `.gitignore` | Sửa (tạo nếu chưa có) | Chặn các file `.env*` chứa secret |
| `README.md` | Sửa | Tài liệu vận hành staging/prod |
| `docker-compose.yaml` | Giữ nguyên | Dev (vẫn build từ Dockerfile multi-stage mới) |

## Thiết kế chi tiết

### 1. `docker-compose.staging.yaml` / `docker-compose.production.yaml`

Hai file gần giống nhau; chỉ khác `SPRING_PROFILES_ACTIVE`, tag `image`, và default resource. Mẫu (production; staging đổi `production`→`staging` và default nhẹ hơn):

```yaml
services:
  api:
    build:
      context: ./
      dockerfile: Dockerfile
    image: api:production
    restart: unless-stopped
    ports:
      - "${APP_PORT:-9000}:9000"
    environment:
      SPRING_PROFILES_ACTIVE: production
      PORT: 9000
      LOG_PATH: ${LOG_PATH:-/var/log/app}
      JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:--XX:MaxRAMPercentage=75}
      POSTGRES_HOST: ${POSTGRES_HOST:?POSTGRES_HOST is required}
      POSTGRES_PORT: ${POSTGRES_PORT:-5432}
      POSTGRES_DB: ${POSTGRES_DB:?POSTGRES_DB is required}
      POSTGRES_USER: ${POSTGRES_USER:?POSTGRES_USER is required}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}
      JWT_SECRET: ${JWT_SECRET:?JWT_SECRET is required}
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:9000/api/v1/actuator/health || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 60s
    deploy:
      resources:
        limits:
          cpus: "${CPU_LIMIT:-1}"
          memory: "${MEM_LIMIT:-1g}"
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "5"
```

Khác biệt staging: `SPRING_PROFILES_ACTIVE: staging`, `image: api:staging`, default `MEM_LIMIT:-512m`.

Lý do thiết kế:
- **Không `depends_on`/Postgres**: DB ngoài, app tự retry kết nối khi khởi động.
- **Secret `:?`**: thiếu biến → compose dừng với thông báo rõ, tránh boot ngầm với secret rỗng (JWT secret rỗng sẽ làm `JwtTokenService` ném lỗi khởi động — fail-fast là đúng).
- **`deploy.resources.limits`**: Compose v2 áp dụng cho `docker compose up` (không cần Swarm).
- **`restart: unless-stopped`**: tự dậy lại khi crash, nhưng tôn trọng khi admin chủ động dừng.
- **healthcheck bằng `curl`** tới actuator health (đã whitelist, không cần token) → tín hiệu "thật" hơn check cổng.

### 2. `Dockerfile` multi-stage

```dockerfile
# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# copy toàn bộ pom để cache layer resolve dependency
COPY pom.xml pom.xml
COPY framework/pom.xml framework/pom.xml
COPY security/pom.xml security/pom.xml
COPY entity/pom.xml entity/pom.xml
COPY dto/pom.xml dto/pom.xml
COPY persistence/pom.xml persistence/pom.xml
COPY business/pom.xml business/pom.xml
COPY business/business-interface/pom.xml business/business-interface/pom.xml
COPY business/business-implementation/pom.xml business/business-implementation/pom.xml
COPY web/pom.xml web/pom.xml
COPY web/api/pom.xml web/api/pom.xml
COPY mybatis-generator/pom.xml mybatis-generator/pom.xml
COPY mybatis-schema-migration/pom.xml mybatis-schema-migration/pom.xml
COPY batch/pom.xml batch/pom.xml
RUN mvn -am -pl web/api -DskipTests dependency:go-offline
# copy source rồi build
COPY framework framework
COPY security security
COPY entity entity
COPY dto dto
COPY persistence persistence
COPY business business
COPY web web
RUN mvn -am -pl web/api clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /app/web/api/target/api-0.0.1-SNAPSHOT.jar app.jar
RUN chown app:app app.jar
USER app
EXPOSE 9000
CMD ["java", "-jar", "app.jar"]
```

Lý do:
- `dependency:go-offline` thay `dependency:resolve` để cache dependency triệt để hơn trước khi copy source.
- Runtime `eclipse-temurin:21-jre`: bỏ Maven + JDK, chỉ JRE → nhỏ hơn nhiều, ít bề mặt tấn công.
- `curl` cài tối thiểu cho healthcheck của compose.
- Chạy `USER app` (non-root) — chuẩn hardening prod.
- `mybatis-generator`/`mybatis-schema-migration` chỉ cần pom để reactor parse (không build code của chúng vì `-pl web/api -am` không kéo chúng vào trừ khi là dependency — chúng không phải dependency của web/api).

### 3. `.env.example`

```dotenv
# Sao chép thành .env.staging / .env.production rồi điền giá trị thật (KHÔNG commit).
# Chạy: docker compose --env-file .env.production -f docker-compose.production.yaml up -d --build

# --- Database ngoài (BẮT BUỘC) ---
POSTGRES_HOST=
POSTGRES_PORT=5432
POSTGRES_DB=
POSTGRES_USER=
POSTGRES_PASSWORD=

# --- Security (BẮT BUỘC) ---
# Chuỗi ngẫu nhiên >= 32 bytes cho HS256
JWT_SECRET=

# --- Tuỳ chọn (có default trong compose) ---
APP_PORT=9000
LOG_PATH=/var/log/app
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75
CPU_LIMIT=1
MEM_LIMIT=1g
```

### 4. `.gitignore`

Thêm (tạo file nếu chưa có):
```gitignore
.env
.env.*
!.env.example
```

### 5. README

Thêm mục "Chạy staging/production": biến bắt buộc, lệnh `docker compose --env-file ... -f docker-compose.<env>.yaml up -d --build`, và nhắc **chạy `mybatis-schema-migration` lên DB đích trước** (init script không chạy ở các môi trường này).

## Verification

1. `docker compose -f docker-compose.production.yaml config` (và staging) parse OK; thiếu secret → báo lỗi rõ ràng đúng như thiết kế.
2. Build multi-stage thành công; `docker images` cho thấy image runtime nhỏ hơn đáng kể so với bản fat hiện tại; ảnh chạy non-root (`docker run ... id` → uid khác 0).
3. Dev compose hiện có (`docker-compose.yaml`) **vẫn chạy** với Dockerfile multi-stage mới: `docker compose up --build -d` → login/health/CRUD như cũ (regression check), rồi `down -v`.
4. Smoke staging/prod bằng một Postgres "đóng vai DB ngoài" (container Postgres chạy riêng, KHÔNG trong compose này) + inject env: app khởi động, `/api/v1/actuator/health` = 200, login admin (sau khi đã chạy migration + seed thủ công) trả token.

## Các bước tiếp theo

1. Ghi spec này (xong) + commit.
2. Chủ dự án review spec.
3. Chuyển sang **writing-plans** lập kế hoạch chi tiết.

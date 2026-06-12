# Docker Compose cho Staging & Production + Multi-stage Dockerfile + Migration tham số hoá — Design Spec

**Ngày:** 2026-06-13
**Nhánh:** `feature/staging-prod-compose`

## Bối cảnh

Dự án `springboot-multi-module` hiện chỉ có một file [docker-compose.yaml](../../../docker-compose.yaml) dùng cho **dev**:
- Build image tại chỗ từ [Dockerfile](../../../Dockerfile) (hiện là "fat image": cả môi trường Maven, chạy `java -jar` ngay trên image build).
- Chạy `postgres:16` ngay trong compose, password hardcode `123456`.
- `JWT_SECRET` có default dev.
- Mount `docker/postgres-init` → init script chạy lần đầu khi volume rỗng (tạo bảng RBAC + seed). Chỉ hợp cho dev.

App điều khiển hoàn toàn qua biến môi trường: `SPRING_PROFILES_ACTIVE`, `POSTGRES_HOST/PORT/DB/USER/PASSWORD`, `JWT_SECRET`, `LOG_PATH`, `PORT`. Profile `staging`/`production` đã có trong [web/api application.yml](../../../web/api/src/main/resources/application.yml).

Migration chạy bằng `migrations-maven-plugin` (`mvn -pl mybatis-schema-migration migration:up -Dmigration.env=<env>`), đọc `environments/<env>.properties`. Các file này hiện **hardcode** `localhost` + username/password.

## Mục tiêu

File compose **riêng cho staging & production** phù hợp vận hành thật, Dockerfile **multi-stage** (runtime gọn, non-root), và **tham số hoá connection của migration** (không hardcode secret) kèm hướng dẫn chạy.

## Quyết định đã chốt (qua brainstorming)

| Hạng mục | Quyết định |
|---|---|
| Database staging & prod | **DB ngoài/managed** cho cả hai. Compose chỉ có service `api`. KHÔNG Postgres/volume/init script. |
| Quản lý secret | **Inject lúc run** (shell/CI hoặc `--env-file`). Compose tham chiếu `${VAR}`; secret dùng `:?` → thiếu là `up` fail. Không commit giá trị thật. |
| Nguồn image | **Build tại chỗ** từ `Dockerfile`. |
| Cấu trúc file compose | **2 file độc lập** `docker-compose.staging.yaml` + `docker-compose.production.yaml`. Giữ `docker-compose.yaml` làm dev. |
| Dockerfile | **Multi-stage** (build → runtime JRE slim, non-root, có `curl`). |
| Migration tham số hoá | **Trong scope.** Dùng script wrapper sinh env file từ biến môi trường (xem §6). |

### Phát hiện kỹ thuật (đã probe)

`migrations-maven-plugin 1.1.3` **KHÔNG** thay thế placeholder `${...}` trong file `.properties` — cả qua `-Dkey=val` lẫn biến môi trường export đều bị giữ nguyên literal (JDBC báo `Unable to parse URL jdbc:postgresql://${POSTGRES_HOST}...`). Vì vậy không thể chỉ đặt `${...}` vào env file; phải sinh env file ở tầng shell (§6).

## Phạm vi (Scope)

**Trong scope:**
1. Tạo `docker-compose.staging.yaml` + `docker-compose.production.yaml` (chỉ service `api`).
2. Chuyển `Dockerfile` sang multi-stage (build + runtime JRE slim, non-root, có `curl`).
3. Thêm `.env.example` (liệt kê biến, không giá trị thật).
4. Cập nhật `.gitignore`: chặn `.env*` và các env file migration chứa secret.
5. **Tham số hoá migration**: script `mybatis-schema-migration/migrate.sh` sinh `environments/<env>.properties` từ biến môi trường; gỡ `staging.properties`/`production.properties` khỏi git + gitignore.
6. Cập nhật README: chạy staging/prod, chạy migration tham số hoá, biến bắt buộc.

**Ngoài scope (follow-up):**
- CI/CD pipeline, registry, reverse proxy/TLS, orchestrator (k8s/swarm).
- Migration service chạy trong compose.

## File structure

| File | Hành động | Trách nhiệm |
|---|---|---|
| `docker-compose.staging.yaml` | Tạo | Chạy `api` profile `staging`, DB ngoài |
| `docker-compose.production.yaml` | Tạo | Chạy `api` profile `production`, DB ngoài |
| `Dockerfile` | Sửa | Multi-stage: build (maven) → runtime (`eclipse-temurin:21-jre`, non-root, `curl`) |
| `.env.example` | Tạo | Liệt kê biến cần inject (không giá trị thật) |
| `.gitignore` | Sửa/tạo | Chặn `.env*` + `environments/{staging,production}.properties` |
| `mybatis-schema-migration/migrate.sh` | Tạo | Wrapper sinh env file từ biến môi trường + chạy migration |
| `.../environments/staging.properties` | `git rm` | Sinh lúc run (gitignore) |
| `.../environments/production.properties` | `git rm` | Sinh lúc run (gitignore) |
| `.../environments/development.properties`, `local.properties` | Giữ nguyên | Dev/local tiện chạy trực tiếp (localhost) |
| `README.md` | Sửa | Hướng dẫn vận hành staging/prod + migration |
| `docker-compose.yaml` | Giữ nguyên | Dev (vẫn build từ Dockerfile multi-stage mới) |

## Thiết kế chi tiết

### 1. `docker-compose.staging.yaml` / `docker-compose.production.yaml`

Mẫu (production; staging đổi `production`→`staging`, default resource nhẹ hơn):

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

Staging khác: `SPRING_PROFILES_ACTIVE: staging`, `image: api:staging`, default `MEM_LIMIT:-512m`.

Lý do: không `depends_on`/Postgres (DB ngoài, app tự retry); secret `:?` fail-fast; `deploy.resources.limits` áp dụng cho `compose up` (Compose v2); healthcheck `curl` tới actuator health (đã whitelist).

### 2. `Dockerfile` multi-stage

```dockerfile
# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
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

Lý do: `go-offline` cache dependency trước khi copy source; runtime `21-jre` bỏ Maven/JDK → nhỏ hơn nhiều; `curl` tối thiểu cho healthcheck; chạy non-root. (Nếu `go-offline` tải thiếu plugin khi build, bước `package` vẫn tự kéo nốt — verify lúc execute.)

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

```gitignore
# Secret env files (giá trị thật, không commit)
.env
.env.*
!.env.example

# Migration env file sinh lúc run (chứa secret)
mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/staging.properties
mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/production.properties
```

### 5. README

Thêm 2 mục: (a) "Chạy staging/production" (biến bắt buộc + `docker compose --env-file ... -f docker-compose.<env>.yaml up -d --build`), (b) "Chạy migration" (xem §6) — nhấn mạnh **migration là tiền đề** trước khi app khởi động (init script không chạy ở các môi trường này).

### 6. Migration tham số hoá — `mybatis-schema-migration/migrate.sh`

Vì plugin không resolve `${...}`, script sinh env file từ biến môi trường (heredoc bash — an toàn với ký tự đặc biệt trong password), chạy migration, rồi **xoá file** (không để secret trên đĩa):

```bash
#!/usr/bin/env bash
# Chạy migration với connection inject qua biến môi trường (không hardcode secret).
# Usage: POSTGRES_HOST=.. POSTGRES_DB=.. POSTGRES_USER=.. POSTGRES_PASSWORD=.. \
#          ./migrate.sh <env> <command> [extra mvn args...]
# VD:  ./migrate.sh production up
#      ./migrate.sh staging status
#      ./migrate.sh production down -Dmigration.down.steps=1
set -euo pipefail

ENV_NAME="${1:?env name required (staging|production|...)}"
COMMAND="${2:?command required (up|down|status|pending|new)}"
shift 2

: "${POSTGRES_HOST:?POSTGRES_HOST is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/src/main/resources/co/jp/htkk/migration/environments/${ENV_NAME}.properties"
cleanup() { rm -f "$ENV_FILE"; }
trap cleanup EXIT

cat > "$ENV_FILE" <<EOF
time_zone=GMT+0:00
script_char_set=UTF-8
driver=org.postgresql.Driver
url=jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}
username=${POSTGRES_USER}
password=${POSTGRES_PASSWORD}
send_full_script=false
delimiter=;
full_line_delimiter=false
auto_commit=false
changelog=CHANGELOG
EOF

cd "$SCRIPT_DIR/.."
mvn -pl mybatis-schema-migration "migration:${COMMAND}" -Dmigration.env="${ENV_NAME}" "$@"
```

Hướng dẫn chạy (đưa vào README):
```bash
export POSTGRES_HOST=db.internal POSTGRES_PORT=5432 POSTGRES_DB=app \
       POSTGRES_USER=app_user POSTGRES_PASSWORD='***'
# Xem trạng thái
./mybatis-schema-migration/migrate.sh production status
# Áp dụng migration
./mybatis-schema-migration/migrate.sh production up
# Rollback 1 bước
./mybatis-schema-migration/migrate.sh production down -Dmigration.down.steps=1
```
Dev/local vẫn chạy trực tiếp như cũ: `mvn -pl mybatis-schema-migration migration:up -Dmigration.env=local`.

## Verification

1. `docker compose -f docker-compose.production.yaml config` (và staging) parse OK; thiếu secret → lỗi rõ ràng.
2. Build multi-stage thành công; image runtime nhỏ hơn đáng kể bản fat; chạy non-root (`docker run … id -u` ≠ 0).
3. Dev compose hiện có **vẫn chạy** với Dockerfile multi-stage mới: `up --build -d` → login/health/CRUD OK (regression), rồi `down -v`.
4. **Migration**: dựng 1 Postgres "đóng vai DB ngoài" (container riêng, KHÔNG trong compose này), export biến, chạy `./migrate.sh staging status` → kết nối tới host đã resolve (không còn `${...}`); `up` tạo bảng; `migrate.sh` xoá env file sau khi chạy (`git status` sạch). Sau đó app (staging compose, cùng biến) khởi động, `/api/v1/actuator/health` = 200.

## Các bước tiếp theo

1. Cập nhật spec (xong) + commit.
2. Chủ dự án review spec.
3. Chuyển sang **writing-plans**.

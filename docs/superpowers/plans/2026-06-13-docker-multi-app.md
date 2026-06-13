# Docker Multi-App Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor Dockerfile + docker-compose để hỗ trợ build/deploy nhiều module application (api, batch, future apps) độc lập, qua parameterized Dockerfile (`ARG APP_MODULE`) + folder `docker/<app>/<env>.yaml` pattern.

**Architecture:** 1 Dockerfile root parameterized build bất kỳ module Spring Boot fat JAR nào. Postgres dev có compose riêng tạo external network `pms-net`. Mỗi app có folder `docker/<app>/` chứa 3 compose files per env (dev/staging/production). Compose dev join `pms-net` external; compose staging/prod kết nối DB external qua env vars.

**Tech Stack:** Docker 23+ (BuildKit default), Docker Compose v2, Java 21, Maven 3.9, Spring Boot 3.3.5, PostgreSQL 16.

**Spec:** `docs/superpowers/specs/2026-06-13-docker-multi-app-design.md`

**Branch:** `refactor/docker-multi-app` (tạo mới từ `main`).

**Prerequisites:**
- Đang ở branch `main`, working tree sạch (`git status` clean).
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` có sẵn (cần khi verify Maven build).
- Docker Desktop / engine v23+ chạy, có quyền build image.
- Port 5432 và 9000 trống trên host (để test compose dev).

---

## Task 1: Branch + folder skeleton + move postgres init

**Files:**
- Create directory: `docker/postgres/`, `docker/api/`, `docker/batch/`
- Move (`git mv`): `docker/postgres-init/` → `docker/postgres/init/`

### - [ ] Step 1: Verify clean state + create branch

Run:
```bash
git status
git rev-parse --abbrev-ref HEAD
```
Expected: working tree clean, branch `main`. Nếu KHÔNG sạch hoặc không ở `main` → stop, hỏi user.

Run:
```bash
git checkout -b refactor/docker-multi-app
```
Expected: `Switched to a new branch 'refactor/docker-multi-app'`.

### - [ ] Step 2: Tạo folder skeleton

Run:
```bash
mkdir -p docker/postgres docker/api docker/batch
```
Expected: 3 folders mới tạo (postgres-init/ cũ vẫn còn). `ls docker/` cho ra `api batch postgres postgres-init`.

### - [ ] Step 3: Move postgres init seed sang folder mới

Run:
```bash
git mv docker/postgres-init docker/postgres/init
```
Expected: `ls docker/postgres/init/` cho ra `01-create-users.sql`. `ls docker/` còn `api batch postgres`.

### - [ ] Step 4: Commit

Run:
```bash
git status
git add docker/
git commit -m "refactor(docker): create per-app folder skeleton + move postgres init

- docker/{postgres,api,batch}/ folders (compose files sẽ tạo ở task sau)
- docker/postgres-init/ → docker/postgres/init/ (cùng namespace với compose postgres)"
```
Expected: commit thành công, working tree clean.

---

## Task 2: Parameterize Dockerfile (ARG APP_MODULE)

**Files:**
- Modify: `Dockerfile` (full rewrite — đổi từ hardcoded api sang parameterized)
- Modify (optional, sẽ kiểm tra): `.dockerignore` (nếu chưa có)

### - [ ] Step 1: Đọc Dockerfile hiện tại để hiểu state

Run:
```bash
cat Dockerfile
```
Expected: thấy các dòng `COPY application/api/pom.xml`, `mvn -am -pl application/api`, `COPY --from=build /app/application/api/target/api-0.0.1-SNAPSHOT.jar app.jar`, `EXPOSE 9000`.

### - [ ] Step 2: Ghi đè Dockerfile với version parameterized

Write to `Dockerfile`:
```dockerfile
# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
ARG APP_MODULE=api
WORKDIR /app

# Copy ALL pom files (dependency-cache layer)
COPY pom.xml pom.xml
COPY framework/pom.xml framework/pom.xml
COPY security/pom.xml security/pom.xml
COPY entity/pom.xml entity/pom.xml
COPY dto/pom.xml dto/pom.xml
COPY persistence/pom.xml persistence/pom.xml
COPY business/pom.xml business/pom.xml
COPY business/business-interface/pom.xml business/business-interface/pom.xml
COPY business/business-implementation/pom.xml business/business-implementation/pom.xml
COPY application/pom.xml application/pom.xml
COPY application/api/pom.xml application/api/pom.xml
COPY application/batch/pom.xml application/batch/pom.xml
COPY mybatis-generator/pom.xml mybatis-generator/pom.xml
COPY mybatis-schema-migration/pom.xml mybatis-schema-migration/pom.xml
RUN mvn -am -pl application/${APP_MODULE} -DskipTests dependency:go-offline

# Copy library + target app source
COPY framework framework
COPY security security
COPY entity entity
COPY dto dto
COPY persistence persistence
COPY business business
COPY application/${APP_MODULE} application/${APP_MODULE}
RUN mvn -am -pl application/${APP_MODULE} clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre AS runtime
ARG APP_MODULE=api
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --no-create-home app \
    && mkdir -p /var/log/app/archived && chown -R app:app /var/log/app
COPY --from=build /app/application/${APP_MODULE}/target/${APP_MODULE}-0.0.1-SNAPSHOT.jar app.jar
RUN chown app:app app.jar
USER app
CMD ["java", "-jar", "app.jar"]
```

**Quan trọng so với cũ:**
- Thêm `ARG APP_MODULE=api` ở build stage và runtime stage (ARG không inherit cross-stage).
- Đổi `COPY application/api/pom.xml` → giữ nguyên (vẫn copy 1 lần cho cache layer, nhưng giờ copy CẢ `application/batch/pom.xml`).
- `mvn -am -pl application/${APP_MODULE}` thay vì hardcode `application/api`.
- Bỏ block `COPY web web` cũ (đã không còn web/ folder), thay bằng từng module library + `COPY application/${APP_MODULE} application/${APP_MODULE}`.
- Bỏ `EXPOSE 9000` — port khai báo ở compose.
- COPY jar runtime: `${APP_MODULE}-0.0.1-SNAPSHOT.jar` thay vì `api-0.0.1-SNAPSHOT.jar`.

### - [ ] Step 3: Verify .dockerignore tồn tại và phù hợp

Run:
```bash
ls -la .dockerignore 2>/dev/null && cat .dockerignore
```

Nếu file KHÔNG tồn tại, tạo `.dockerignore`:
```
target/
**/target/
.git/
.idea/
.vscode/
*.log
.DS_Store
docker/
docs/
README.md
CLAUDE.md
```

Lý do: `docker/` không cần trong build context (compose files). `docs/` không cần build. `target/` (local Maven build output) làm phồng context.

Nếu file đã có và đã chứa `target/`, bỏ qua. Nếu thiếu mục nào trong list trên, append vào.

### - [ ] Step 4: Verify build với APP_MODULE=api (default)

Run:
```bash
docker build --build-arg APP_MODULE=api -t pms-test/api:plan-task2 . 2>&1 | tail -30
```
Expected:
- `BUILD SUCCESS` từ Maven inside build stage.
- Final output: `Successfully tagged pms-test/api:plan-task2` (hoặc `naming to docker.io/library/pms-test/api:plan-task2 done` ở BuildKit).
- Không lỗi `COPY failed`, không lỗi Maven.

Nếu fail vì `COPY application/${APP_MODULE}` không expand biến: BuildKit chưa enabled. Set `DOCKER_BUILDKIT=1` rồi retry: `DOCKER_BUILDKIT=1 docker build ...`.

### - [ ] Step 5: Verify build với APP_MODULE=batch

Run:
```bash
docker build --build-arg APP_MODULE=batch -t pms-test/batch:plan-task2 . 2>&1 | tail -30
```
Expected: `BUILD SUCCESS` từ Maven (reactor list KHÔNG có `security`, có `batch`). Image tag thành công.

Nếu fail vì `Cannot resolve dependency jp.co.htkk:business-implementation`: kiểm tra Step 2 đã copy đủ `application/pom.xml` chưa (application/ là parent có business-implementation dep).

### - [ ] Step 6: Verify image api chạy được (smoke test)

Run:
```bash
docker run --rm -d --name pms-api-smoke \
  -p 9001:9000 \
  -e SPRING_PROFILES_ACTIVE=development \
  -e POSTGRES_HOST=host.docker.internal \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_DB=helpo_step \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=123456 \
  -e JWT_SECRET=smoke-test-secret-please-change-0123456789-abc \
  pms-test/api:plan-task2
sleep 15
docker logs pms-api-smoke 2>&1 | tail -20
docker stop pms-api-smoke
```
Expected: trong logs thấy `Started PointManagementSysApplication` hoặc warning DB không connect được (vì không có postgres) — KHÔNG có lỗi `ClassNotFoundException`, `NoClassDefFoundError`, `Aspect` class missing. Container chạy được tới Spring Boot startup.

(Smoke test này không yêu cầu DB thực, chỉ kiểm tra JAR + dependencies OK.)

### - [ ] Step 7: Cleanup test images

Run:
```bash
docker rmi pms-test/api:plan-task2 pms-test/batch:plan-task2
```
Expected: 2 images deleted. `docker images | grep pms-test` trả rỗng.

### - [ ] Step 8: Commit

Run:
```bash
git add Dockerfile .dockerignore
git status   # phải chỉ thấy Dockerfile (modified) và optional .dockerignore (modified/new)
git commit -m "refactor(docker): parameterize Dockerfile via ARG APP_MODULE

- ARG APP_MODULE (default 'api') cho build + runtime stage
- COPY ALL pom files (cache layer), build chỉ module target qua -am -pl
- Bỏ EXPOSE (port khai báo ở compose)
- Verified: build api + batch images thành công, api smoke-runs"
```

---

## Task 3: Postgres dev compose + xóa root compose files cũ

**Files:**
- Create: `docker/postgres/docker-compose.dev.yaml`
- Delete: `docker-compose.yaml`, `docker-compose.staging.yaml`, `docker-compose.production.yaml`

### - [ ] Step 1: Tạo `docker/postgres/docker-compose.dev.yaml`

Write to `docker/postgres/docker-compose.dev.yaml`:
```yaml
services:
  postgres:
    image: postgres:16
    container_name: pms-postgres
    volumes:
      - data:/var/lib/postgresql/data
      - ./init:/docker-entrypoint-initdb.d:ro
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: 123456
      POSTGRES_DB: helpo_step
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d helpo_step"]
      interval: 5s
      timeout: 5s
      retries: 5
    networks:
      - pms-net

networks:
  pms-net:
    name: pms-net

volumes:
  data:
```

**Quan trọng:**
- `name: pms-net` ở `networks:` đặt tên explicit (mặc định Docker prefix bằng project name) — cho phép compose khác `external: true` join cùng tên.
- `./init` là relative tới file compose → trỏ đến `docker/postgres/init/` (đã tạo ở Task 1).
- `container_name: pms-postgres` thay tên cũ `postgresDb`.

### - [ ] Step 2: Xóa 3 root compose files cũ

Run:
```bash
git rm docker-compose.yaml docker-compose.staging.yaml docker-compose.production.yaml
ls *.yaml 2>/dev/null
```
Expected: 3 files được stage để xóa. `ls *.yaml` không có dòng compose nào (chỉ có thể có file YAML khác như openapi, nhưng chỉ thấy "no matches" hoặc list rỗng).

### - [ ] Step 3: Verify postgres compose up

Run:
```bash
docker compose -f docker/postgres/docker-compose.dev.yaml up -d
sleep 10
docker compose -f docker/postgres/docker-compose.dev.yaml ps
docker network ls | grep pms-net
docker exec pms-postgres pg_isready -U postgres -d helpo_step
```
Expected:
- `docker compose ... ps` cho thấy service `postgres` STATUS `(healthy)`.
- `docker network ls` có dòng `pms-net`.
- `pg_isready` trả `accepting connections`.

### - [ ] Step 4: Verify seed RBAC đã chạy

Run:
```bash
docker exec pms-postgres psql -U postgres -d helpo_step -c "\dt"
```
Expected: liệt kê bảng `users`, `roles`, `permissions`, `user_roles`, `role_permissions` (theo `01-create-users.sql`).

Run:
```bash
docker exec pms-postgres psql -U postgres -d helpo_step -c "SELECT username FROM users;"
```
Expected: thấy `admin` và `normal` (hoặc tên user trong seed).

Nếu seed KHÔNG chạy: kiểm tra `docker/postgres/init/01-create-users.sql` có tồn tại không (`ls docker/postgres/init/`). Volume `data` cũ có thể có lingering — `docker compose ... down -v` rồi up lại.

### - [ ] Step 5: Tear down postgres

Run:
```bash
docker compose -f docker/postgres/docker-compose.dev.yaml down -v
docker network ls | grep pms-net || echo "pms-net removed"
```
Expected: containers + volume xóa sạch. `pms-net` cũng xóa.

### - [ ] Step 6: Commit

Run:
```bash
git add docker/postgres/docker-compose.dev.yaml
git status   # 3 file root deleted + 1 file mới
git commit -m "refactor(docker): split postgres into docker/postgres/docker-compose.dev.yaml

- docker/postgres/docker-compose.dev.yaml: postgres-only, tạo external network pms-net
- Container name: postgresDb → pms-postgres
- Xóa docker-compose.{,staging,production}.yaml ở root (sẽ thay bằng per-app compose ở task sau)
- Verified: postgres up healthy, seed RBAC chạy, network pms-net xuất hiện"
```

---

## Task 4: API compose files (3 envs)

**Files:**
- Create: `docker/api/docker-compose.dev.yaml`
- Create: `docker/api/docker-compose.staging.yaml`
- Create: `docker/api/docker-compose.production.yaml`

### - [ ] Step 1: Tạo `docker/api/docker-compose.dev.yaml`

Write to `docker/api/docker-compose.dev.yaml`:
```yaml
services:
  api:
    image: api:1.0.0
    build:
      context: ../..
      dockerfile: Dockerfile
      args:
        APP_MODULE: api
    container_name: pms-api
    ports:
      - "9000:9000"
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-development}
      LOG_PATH: ${LOG_PATH:-/var/log/app}
      POSTGRES_HOST: postgres
      POSTGRES_PORT: 5432
      POSTGRES_DB: helpo_step
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: 123456
      JWT_SECRET: ${JWT_SECRET:-dev-secret-please-change-0123456789-abcdefghij}
    networks:
      - pms-net

networks:
  pms-net:
    external: true
```

**Quan trọng:**
- `context: ../..` → repo root (vì compose ở `docker/api/`).
- `args: { APP_MODULE: api }` truyền vào Dockerfile ARG.
- `external: true` → join network đã tạo bởi postgres compose. Postgres PHẢI start trước.
- `POSTGRES_HOST: postgres` → service name trong postgres compose (DNS qua docker network).

### - [ ] Step 2: Tạo `docker/api/docker-compose.staging.yaml`

Write to `docker/api/docker-compose.staging.yaml`:
```yaml
services:
  api:
    build:
      context: ../..
      dockerfile: Dockerfile
      args:
        APP_MODULE: api
    image: api:staging
    container_name: pms-api
    restart: unless-stopped
    ports:
      - "${APP_PORT:-9000}:9000"
    environment:
      SPRING_PROFILES_ACTIVE: staging
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
          memory: "${MEM_LIMIT:-512m}"
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "5"
```

**Lưu ý:**
- KHÔNG có `networks:` (staging dùng external managed DB, không cần network nội bộ).
- `${VAR:?msg}` → docker compose fail-fast nếu thiếu env var quan trọng.

### - [ ] Step 3: Tạo `docker/api/docker-compose.production.yaml`

Write to `docker/api/docker-compose.production.yaml`:
```yaml
services:
  api:
    build:
      context: ../..
      dockerfile: Dockerfile
      args:
        APP_MODULE: api
    image: api:production
    container_name: pms-api
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
          memory: "${MEM_LIMIT:-512m}"
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "5"
```

Khác staging: `image: api:production` và `SPRING_PROFILES_ACTIVE: production`.

### - [ ] Step 4: Verify dev compose (postgres + api)

Run:
```bash
docker compose -f docker/postgres/docker-compose.dev.yaml up -d
sleep 5
docker compose -f docker/api/docker-compose.dev.yaml up --build -d
echo "Waiting 60s for api startup..."
sleep 60
docker compose -f docker/api/docker-compose.dev.yaml ps
docker logs pms-api 2>&1 | tail -20
```
Expected:
- Service `api` STATUS UP (không có `Exit code`).
- Trong logs: `Started PointManagementSysApplication in N.N seconds`.

### - [ ] Step 5: Health check + login smoke test

Run:
```bash
curl -fsS http://localhost:9000/api/v1/actuator/health
echo ""
curl -fsS -X POST http://localhost:9000/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
echo ""
```
Expected:
- Health: `{"status":"UP"}`.
- Login: JSON với `accessToken`, `tokenType: "Bearer"`, `expiresIn: 1800`.

Nếu login fail (vd 401): seed user khác hoặc password khác. Đọc `docker/postgres/init/01-create-users.sql` để confirm username/password chính xác và sửa cmd.

### - [ ] Step 6: Tear down

Run:
```bash
docker compose -f docker/api/docker-compose.dev.yaml down
docker compose -f docker/postgres/docker-compose.dev.yaml down -v
```
Expected: cả 2 stack down sạch, volume xóa.

### - [ ] Step 7: Validate staging/production YAML syntax (config dry-run)

Vì không có infra staging/prod thật để test full, dùng `docker compose config` để verify YAML hợp lệ và env var binding đúng.

Run:
```bash
POSTGRES_HOST=fake-db POSTGRES_DB=fake POSTGRES_USER=fake POSTGRES_PASSWORD=fake JWT_SECRET=fake-secret-32-bytes-minimum-1234567890 \
  docker compose -f docker/api/docker-compose.staging.yaml config > /dev/null
echo "staging config OK: $?"

POSTGRES_HOST=fake-db POSTGRES_DB=fake POSTGRES_USER=fake POSTGRES_PASSWORD=fake JWT_SECRET=fake-secret-32-bytes-minimum-1234567890 \
  docker compose -f docker/api/docker-compose.production.yaml config > /dev/null
echo "production config OK: $?"
```
Expected: cả 2 dòng print `OK: 0` (exit code 0). Nếu lỗi → kiểm tra YAML indentation / typo.

Verify env validation fail-fast:
```bash
docker compose -f docker/api/docker-compose.staging.yaml config 2>&1 | grep -i 'required'
```
Expected: thấy ít nhất 1 dòng kiểu `... POSTGRES_HOST is required`. Tức là `${VAR:?}` validation đang work.

### - [ ] Step 8: Commit

Run:
```bash
git add docker/api/
git commit -m "refactor(docker): add docker/api/docker-compose.{dev,staging,production}.yaml

- api dev: join external pms-net, mặc định JWT_SECRET cho local
- api staging/prod: env vars ${VAR:?} fail-fast, healthcheck via /actuator/health,
  resource limits + log rotation
- Container name: api → pms-api
- Verified: dev postgres + api up healthy, health endpoint 200, login OK.
  Staging/prod YAML hợp lệ qua docker compose config"
```

---

## Task 5: Batch compose files (3 envs)

**Files:**
- Create: `docker/batch/docker-compose.dev.yaml`
- Create: `docker/batch/docker-compose.staging.yaml`
- Create: `docker/batch/docker-compose.production.yaml`

### - [ ] Step 1: Tạo `docker/batch/docker-compose.dev.yaml`

Write to `docker/batch/docker-compose.dev.yaml`:
```yaml
services:
  batch:
    image: batch:1.0.0
    build:
      context: ../..
      dockerfile: Dockerfile
      args:
        APP_MODULE: batch
    container_name: pms-batch
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-development}
      LOG_PATH: ${LOG_PATH:-/var/log/app}
      POSTGRES_HOST: postgres
      POSTGRES_PORT: 5432
      POSTGRES_DB: helpo_step
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: 123456
    networks:
      - pms-net

networks:
  pms-net:
    external: true
```

**Khác api dev:**
- KHÔNG có `ports:` (batch không expose HTTP cho external access).
- KHÔNG có `JWT_SECRET` (batch không dùng security module).

### - [ ] Step 2: Tạo `docker/batch/docker-compose.staging.yaml`

Write to `docker/batch/docker-compose.staging.yaml`:
```yaml
services:
  batch:
    build:
      context: ../..
      dockerfile: Dockerfile
      args:
        APP_MODULE: batch
    image: batch:staging
    container_name: pms-batch
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: staging
      LOG_PATH: ${LOG_PATH:-/var/log/app}
      JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:--XX:MaxRAMPercentage=75}
      POSTGRES_HOST: ${POSTGRES_HOST:?POSTGRES_HOST is required}
      POSTGRES_PORT: ${POSTGRES_PORT:-5432}
      POSTGRES_DB: ${POSTGRES_DB:?POSTGRES_DB is required}
      POSTGRES_USER: ${POSTGRES_USER:?POSTGRES_USER is required}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}
    deploy:
      resources:
        limits:
          cpus: "${CPU_LIMIT:-1}"
          memory: "${MEM_LIMIT:-512m}"
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "5"
```

KHÔNG có `healthcheck` (chưa có endpoint health/liveness cho batch; bổ sung khi service batch thật được implement).

### - [ ] Step 3: Tạo `docker/batch/docker-compose.production.yaml`

Write to `docker/batch/docker-compose.production.yaml`:
```yaml
services:
  batch:
    build:
      context: ../..
      dockerfile: Dockerfile
      args:
        APP_MODULE: batch
    image: batch:production
    container_name: pms-batch
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: production
      LOG_PATH: ${LOG_PATH:-/var/log/app}
      JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:--XX:MaxRAMPercentage=75}
      POSTGRES_HOST: ${POSTGRES_HOST:?POSTGRES_HOST is required}
      POSTGRES_PORT: ${POSTGRES_PORT:-5432}
      POSTGRES_DB: ${POSTGRES_DB:?POSTGRES_DB is required}
      POSTGRES_USER: ${POSTGRES_USER:?POSTGRES_USER is required}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}
    deploy:
      resources:
        limits:
          cpus: "${CPU_LIMIT:-1}"
          memory: "${MEM_LIMIT:-512m}"
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "5"
```

Khác staging: `image: batch:production` và `SPRING_PROFILES_ACTIVE: production`.

### - [ ] Step 4: Verify dev compose (postgres + api + batch đồng thời)

Run:
```bash
docker compose -f docker/postgres/docker-compose.dev.yaml up -d
sleep 5
docker compose -f docker/api/docker-compose.dev.yaml up --build -d
docker compose -f docker/batch/docker-compose.dev.yaml up --build -d
echo "Waiting 60s for both apps to start..."
sleep 60
docker ps --format 'table {{.Names}}\t{{.Status}}'
```
Expected: 3 containers — `pms-postgres` (healthy), `pms-api` (Up), `pms-batch` (Up). Không có Exited.

Nếu `pms-batch` Exit ngay: chưa có job batch thật chạy. Có thể container chạy Spring Boot app rồi hoàn thành Job0001 sample. Kiểm tra logs:
```bash
docker logs pms-batch 2>&1 | tail -30
```
Expected logs: `Started BatchApplication`, có thể có log khác về job. KHÔNG được có `ClassNotFoundException`, `NoClassDefFoundError`.

Nếu batch có một job chạy 1 lần rồi exit, đó là behavior bình thường của Spring Batch — không phải lỗi. Note rằng container exit code 0 là OK.

### - [ ] Step 5: Test network connectivity (batch → postgres)

Run:
```bash
docker exec pms-batch sh -c "getent hosts postgres" 2>&1 || \
  docker logs pms-batch 2>&1 | grep -iE 'postgres|database|connection' | head -10
```
Expected: nếu container vẫn UP, `getent hosts postgres` trả IP. Nếu container đã exit, kiểm tra trong logs đã có nhắc tới connection đến `postgres:5432`.

### - [ ] Step 6: Tear down

Run:
```bash
docker compose -f docker/batch/docker-compose.dev.yaml down
docker compose -f docker/api/docker-compose.dev.yaml down
docker compose -f docker/postgres/docker-compose.dev.yaml down -v
```
Expected: all 3 stacks down sạch.

### - [ ] Step 7: Validate batch staging/production YAML

Run:
```bash
POSTGRES_HOST=fake POSTGRES_DB=fake POSTGRES_USER=fake POSTGRES_PASSWORD=fake \
  docker compose -f docker/batch/docker-compose.staging.yaml config > /dev/null
echo "batch staging config OK: $?"

POSTGRES_HOST=fake POSTGRES_DB=fake POSTGRES_USER=fake POSTGRES_PASSWORD=fake \
  docker compose -f docker/batch/docker-compose.production.yaml config > /dev/null
echo "batch production config OK: $?"
```
Expected: cả 2 dòng print `OK: 0`.

### - [ ] Step 8: Commit

Run:
```bash
git add docker/batch/
git commit -m "refactor(docker): add docker/batch/docker-compose.{dev,staging,production}.yaml

- batch dev: join pms-net external, không expose port, không JWT
- batch staging/prod: env vars ${VAR:?} fail-fast, resource limits + log rotation
- KHÔNG có healthcheck (chưa có endpoint health cho batch, defer khi có job thật)
- Container name: pms-batch
- Verified: dev postgres + api + batch up đồng thời, batch resolve DNS pms-net.
  Staging/prod YAML hợp lệ qua docker compose config"
```

---

## Task 6: Update docs (CLAUDE.md, README.md, .env.example)

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`
- Modify: `.env.example`

### - [ ] Step 1: Đọc các điểm cần sửa trong CLAUDE.md

Run:
```bash
grep -n -E 'docker compose|docker-compose|docker/postgres-init' CLAUDE.md
```
Expected: liệt kê các dòng:
- `# Chạy dev bằng docker compose (postgres:16 + api, có seed RBAC)`
- `JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij docker compose up --build -d`
- `# Dev: docker docker/postgres-init/01-create-users.sql chạy 1 lần khi volume rỗng`

### - [ ] Step 2: Cập nhật CLAUDE.md — section "Lệnh build / test / run"

Replace block:
```markdown
# Chạy dev bằng docker compose (postgres:16 + api, có seed RBAC)
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij docker compose up --build -d
# health (whitelist, không cần token):
curl http://localhost:9000/api/v1/actuator/health
```

With:
```markdown
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

### - [ ] Step 3: Cập nhật CLAUDE.md — section "Migration (DDL)" / dev seed

Tìm dòng nhắc đến `docker/postgres-init/` và replace path:

Replace block:
```markdown
- Dev: docker `docker/postgres-init/01-create-users.sql` chạy **1 lần khi volume rỗng** (không phải công cụ migration).
```

With:
```markdown
- Dev: docker `docker/postgres/init/01-create-users.sql` chạy **1 lần khi volume rỗng** (không phải công cụ migration).
```

### - [ ] Step 4: Cập nhật CLAUDE.md — thêm quy ước Docker multi-app vào section "Coding rules"

Tìm cuối section "Coding rules" trước section "Git / workflow", thêm sub-section mới:

Append before `## Git / workflow`:
```markdown

### Docker multi-app
- 1 `Dockerfile` ở root, parameterized `ARG APP_MODULE` (mặc định `api`). Build app bất kỳ: `docker build --build-arg APP_MODULE=<module> -t <module>:tag .`
- **Quy ước cứng**: folder name dưới `application/` PHẢI = artifactId của module Maven (vd `application/api/` ↔ artifactId `api`). Dockerfile phụ thuộc convention này để tìm jar `${APP_MODULE}-${version}.jar`.
- Mỗi app có folder `docker/<app>/` chứa `docker-compose.{dev,staging,production}.yaml`. Add app mới: copy folder `docker/api/` thành `docker/<new-app>/` và sửa `APP_MODULE` + `image` + ports/env vars.
- Dev compose dùng external network `pms-net` (tạo bởi `docker/postgres/docker-compose.dev.yaml`). Postgres phải start TRƯỚC app compose.
- Khi app KHÔNG phải Spring Boot Java fat JAR (vd Node frontend, native CLI): không dùng root Dockerfile param — tạo Dockerfile riêng trong `application/<app>/Dockerfile`, compose tự khai báo `dockerfile: ../../application/<app>/Dockerfile`.
```

### - [ ] Step 5: Verify CLAUDE.md không còn ref cũ

Run:
```bash
grep -nE 'docker compose up --build|docker/postgres-init|docker-compose\.yaml|docker-compose\.staging|docker-compose\.production' CLAUDE.md
```
Expected: rỗng (hoặc chỉ còn 0 hits).

### - [ ] Step 6: Đọc README.md để xác định các block cần sửa

Run:
```bash
grep -nE 'docker compose|docker-compose|docker/postgres-init|JWT_SECRET=' README.md
```
Expected: liệt kê các dòng nhắc compose ở section "How to run", "Deploy".

### - [ ] Step 7: Cập nhật README.md — section "How to run — local (Docker Compose)"

Tìm và replace block (giả định README có pattern tương tự CLAUDE.md):

Replace block:
```markdown
Dev compose chạy `postgres:16` + `api`, tự tạo bảng RBAC + seed user qua `docker/postgres-init/`:

```bash
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij docker compose up --build -d
# health (whitelist, không cần token):
curl http://localhost:9000/api/v1/actuator/health
# tear down:
docker compose down -v
```
```

With:
```markdown
Dev compose chạy `postgres:16` riêng + `api` riêng (join external network `pms-net`), tự tạo bảng RBAC + seed user qua `docker/postgres/init/`:

```bash
# Bước 1: start postgres (tạo network pms-net)
docker compose -f docker/postgres/docker-compose.dev.yaml up -d
# Bước 2: start api
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij \
  docker compose -f docker/api/docker-compose.dev.yaml up --build -d
# health (whitelist, không cần token):
curl http://localhost:9000/api/v1/actuator/health
# tear down:
docker compose -f docker/api/docker-compose.dev.yaml down
docker compose -f docker/postgres/docker-compose.dev.yaml down -v
```

**Tiện lợi (optional)**: set `COMPOSE_FILE` env để gọn lệnh:
```bash
export COMPOSE_FILE=docker/postgres/docker-compose.dev.yaml:docker/api/docker-compose.dev.yaml
docker compose up --build -d
docker compose down -v
```
```

### - [ ] Step 8: Cập nhật README.md — cây thư mục

Tìm trong block ASCII tree section "Architecture — multi-module":

Replace dòng:
```
├── docker-compose.yaml             # DEV: postgres:16 + api + seed RBAC
├── docker-compose.staging.yaml     # STAGING: chỉ api, DB ngoài
├── docker-compose.production.yaml  # PRODUCTION: chỉ api, DB ngoài
├── Dockerfile                      # multi-stage (build maven → runtime jre slim, non-root)
```

With:
```
├── docker/                         # ── Docker deployment per app per env ──
│   ├── postgres/                   # DEV: postgres compose + init SQL (tạo pms-net)
│   ├── api/                        # api: docker-compose.{dev,staging,production}.yaml
│   └── batch/                      # batch: docker-compose.{dev,staging,production}.yaml
├── Dockerfile                      # parameterized ARG APP_MODULE (multi-stage, non-root)
```

### - [ ] Step 9: Cập nhật README.md — section "Deploy" (nếu có)

Run:
```bash
grep -nE 'staging|production' README.md | head -10
```

Nếu có section "Deploy staging/production" với lệnh cũ `docker compose -f docker-compose.staging.yaml`, replace bằng:
```bash
docker compose -f docker/api/docker-compose.staging.yaml up --build -d
```

(Sửa từng instance theo context. Nếu README không có section này, bỏ qua step.)

### - [ ] Step 10: Verify README không còn ref cũ

Run:
```bash
grep -nE 'docker-compose\.yaml$|docker-compose\.staging|docker-compose\.production|docker/postgres-init|`docker compose up --build`' README.md
```
Expected: rỗng.

### - [ ] Step 11: Cập nhật `.env.example`

Run:
```bash
cat .env.example
```

Append cuối file (nếu chưa có) — thêm comment chỉ cách dùng COMPOSE_FILE alias:
```bash
# Optional: alias lệnh docker compose dev (postgres + api) cho gọn
# export COMPOSE_FILE=docker/postgres/docker-compose.dev.yaml:docker/api/docker-compose.dev.yaml
# sau đó: docker compose up --build -d
```

### - [ ] Step 12: Final docs validation

Run:
```bash
grep -rnE 'docker-compose\.yaml|docker-compose\.staging|docker-compose\.production|docker/postgres-init' \
  --include='*.md' . 2>/dev/null | grep -v 'docs/superpowers/'
```
Expected: rỗng (specs và plans có thể nhắc đến cho lịch sử, ok).

### - [ ] Step 13: Commit

Run:
```bash
git add CLAUDE.md README.md .env.example
git commit -m "docs: update for docker multi-app layout (docker/<app>/<env>.yaml)

- CLAUDE.md: lệnh dev mới (postgres + api compose riêng), thêm section
  'Docker multi-app' quy ước (folder=artifactId, không phải Spring Boot Java
  thì tạo Dockerfile riêng)
- README.md: cây thư mục docker/, lệnh dev/staging mới, tip COMPOSE_FILE alias
- .env.example: comment COMPOSE_FILE alias"
```

---

## Task 7: Final verification + push + PR

**Files:** không sửa, chỉ verify + push.

### - [ ] Step 1: Verify Maven build vẫn pass (không đụng tới Maven layer)

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`. JAR files xuất hiện ở `application/api/target/api-0.0.1-SNAPSHOT.jar` + `application/batch/target/batch-0.0.1-SNAPSHOT.jar`.

### - [ ] Step 2: Full Docker smoke test (api dev + login)

Run:
```bash
docker compose -f docker/postgres/docker-compose.dev.yaml up -d
sleep 5
docker compose -f docker/api/docker-compose.dev.yaml up --build -d
echo "Waiting 60s for api startup..."
sleep 60

# Health
curl -fsS http://localhost:9000/api/v1/actuator/health
echo ""

# Login + verify token
TOKEN=$(curl -fsS -X POST http://localhost:9000/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | grep -oE '"accessToken":"[^"]+' | sed 's/"accessToken":"//')
echo "Token (truncated): ${TOKEN:0:40}..."

# Optional: gọi 1 endpoint cần auth
curl -fsS -H "Authorization: Bearer $TOKEN" http://localhost:9000/api/v1/admin/users | head -c 200
echo ""
```
Expected:
- Health: `{"status":"UP"}`.
- Token: chuỗi JWT bắt đầu bằng `eyJ...`.
- Admin endpoint: JSON response (không 401/403).

### - [ ] Step 3: Tear down all

Run:
```bash
docker compose -f docker/api/docker-compose.dev.yaml down
docker compose -f docker/postgres/docker-compose.dev.yaml down -v
docker images | grep -E 'api:1\.0\.0|batch:1\.0\.0|postgres:16' | head -5
```

### - [ ] Step 4: Validate verification list từ spec

Đối chiếu với mục "Verification" của spec `docs/superpowers/specs/2026-06-13-docker-multi-app-design.md`:

- [x] `mvn clean install ...` BUILD SUCCESS (Step 1)
- [x] `docker build --build-arg APP_MODULE=api` success (Task 2 Step 4)
- [x] `docker build --build-arg APP_MODULE=batch` success (Task 2 Step 5)
- [x] Dev compose postgres → api up, health 200 (Step 2)
- [x] Dev compose postgres + api + batch up đồng thời (Task 5 Step 4)
- [x] Login flow trả token (Step 2)
- [x] `grep -rE 'docker-compose\.yaml|...'` rỗng (Task 6 Step 12)

Nếu mọi item check pass → refactor xong.

### - [ ] Step 5: Final git status check

Run:
```bash
git status
git log --oneline main..HEAD
```
Expected:
- `git status`: working tree clean.
- 6 commits trên branch (1 per task 1-6), KHÔNG có commit nào ở Task 7.

### - [ ] Step 6: Push branch

Run:
```bash
git push -u origin refactor/docker-multi-app
```
Expected: branch được push, URL gợi ý tạo PR in ra.

### - [ ] Step 7: Tạo PR

Run:
```bash
gh pr create --title "refactor(docker): per-app compose folder + parameterized Dockerfile" --body "$(cat <<'EOF'
## Summary
- Parameterized `Dockerfile` (`ARG APP_MODULE`) build bất kỳ Spring Boot fat JAR nào trong `application/<module>`.
- Postgres tách thành compose riêng (`docker/postgres/docker-compose.dev.yaml`), tạo external network `pms-net`.
- Mỗi app có folder `docker/<app>/` chứa `docker-compose.{dev,staging,production}.yaml`. Bắt đầu với `api` + `batch`.
- Xóa 3 root compose files cũ (`docker-compose.{,staging,production}.yaml`).
- Add app mới: copy folder `docker/api/` thành `docker/<new-app>/`, sửa `APP_MODULE` + image + ports/env vars.

Breaking changes:
- `docker compose up` ở root không còn (phải dùng `-f docker/<app>/...` hoặc `export COMPOSE_FILE=...`).
- Container names đổi: `api` → `pms-api`, `postgresDb` → `pms-postgres`.
- Dev order: postgres compose phải start TRƯỚC app compose (tạo network).

Spec: `docs/superpowers/specs/2026-06-13-docker-multi-app-design.md`
Plan: `docs/superpowers/plans/2026-06-13-docker-multi-app.md`

## Test plan
- [ ] `mvn clean install -pl -mybatis-generator,-mybatis-schema-migration` → BUILD SUCCESS
- [ ] `docker build --build-arg APP_MODULE=api -t api:test .` → success
- [ ] `docker build --build-arg APP_MODULE=batch -t batch:test .` → success
- [ ] Dev: postgres + api up, `curl /api/v1/actuator/health` 200, login trả token
- [ ] Dev: postgres + api + batch up đồng thời không xung đột network
- [ ] `docker compose -f docker/api/docker-compose.staging.yaml config` hợp lệ
EOF
)"
```
Expected: PR được tạo, URL in ra (vd `https://github.com/ChoGaoNgon/springboot-multi-module/pull/N`).

---

## Notes về workflow cho engineer thực thi

- **Mỗi Task = 1 commit** — sau khi commit Task 1, immediate verify trước Task 2. Không snowball.
- **Docker BuildKit**: Docker Desktop bật BuildKit mặc định. Nếu chạy Linux engine cũ, `export DOCKER_BUILDKIT=1` trước khi `docker build`.
- **Network `pms-net` lingering**: nếu rollback giữa chừng, `docker network rm pms-net` để clean. Nếu app compose báo "network pms-net not found" → quên start postgres trước.
- **Port 5432/9000 trong dùng**: trước Step verify, kiểm tra `lsof -i:5432`, `lsof -i:9000` — local postgres hoặc app khác chiếm port sẽ làm compose fail.
- **Volume `data` của postgres**: dev compose `down -v` để xóa hẳn. Nếu seed RBAC không apply, thường do volume cũ lingering.
- **Rollback giữa chừng**: `git reset --hard HEAD~1` cho mỗi task (commit thường xuyên = rollback dễ).
- **Smoke test trên image batch chưa exhaustive** — batch chưa có endpoint health rõ ràng, chấp nhận "container UP + logs có Spring Boot startup" là pass. Khi batch job thật được implement, plan sau sẽ thêm test cụ thể hơn.

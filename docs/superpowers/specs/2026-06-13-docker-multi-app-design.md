# Docker Multi-App Layout — Design Spec

**Date:** 2026-06-13
**Topic:** Tổ chức Dockerfile + docker-compose để hỗ trợ nhiều module application (api, batch, và các app Spring Boot tương lai)
**Status:** Approved (brainstorming complete, awaiting implementation plan)

## Goal

Hôm nay chỉ `application/api` có Docker artifact. Module `application/batch` đã tồn tại nhưng không có Dockerfile / compose entry. Refactor sao cho:

- Thêm app Spring Boot mới (vd `application/worker`, `application/scheduler`) chỉ cần tạo compose files, không phải sửa Dockerfile.
- Mỗi app deploy độc lập (start/stop/scale riêng), có compose riêng per env (dev/staging/production).
- Dev experience giữ tương đương: có thể bring up postgres + 1 app bằng 2 lệnh `docker compose -f ... up -d` (chấp nhận verbose hơn cũ, không thêm wrapper script).
- Pattern tổ chức gọn, ai đọc CLAUDE.md/README cũng tự suy ra cách add app mới.

## Decisions

1. **Tất cả app trong `application/` là Spring Boot fat JAR** — cùng base image `eclipse-temurin:21-jre`, cùng entrypoint `java -jar`. Khác nhau ở module path + jar name + env vars + ports.
2. **Mỗi app deploy độc lập** — không gộp tất cả app trong 1 compose stack. Mỗi app 1 file compose per env.
3. **Postgres tách thành compose riêng (dev only)** — staging/prod dùng DB external (managed), không tạo trong compose.
4. **Layout B — subfolder `docker/<app>/`** — group by app. Lý do: scale tốt khi có nhiều app, cấu trúc rõ ràng "1 folder = 1 app deployment unit".
5. **Quy ước cứng**: folder name dưới `application/` PHẢI = artifactId của module Maven. Lý do: Dockerfile param hóa `APP_MODULE` dùng cùng giá trị cho cả path lẫn jar name (`${APP_MODULE}-${version}.jar`).
6. **Không wrapper script / Makefile** — gõ `docker compose -f ...` thẳng, document trong README. Optional: comment trong `.env.example` chỉ cách dùng `COMPOSE_FILE` env var để alias.

## Architecture

### Layout tổng thể

```
springboot-multi-module/
├── Dockerfile                          # parameterized: ARG APP_MODULE=api
├── .dockerignore
├── .env.example
├── docker/
│   ├── postgres/
│   │   ├── docker-compose.dev.yaml     # postgres-only (dev); tạo external network pms-net
│   │   └── init/                       # SQL seed (rename từ docker/postgres-init/)
│   │       └── 01-create-users.sql
│   ├── api/
│   │   ├── docker-compose.dev.yaml     # join pms-net external
│   │   ├── docker-compose.staging.yaml # DB external (env-driven), không network
│   │   └── docker-compose.production.yaml
│   └── batch/
│       ├── docker-compose.dev.yaml
│       ├── docker-compose.staging.yaml
│       └── docker-compose.production.yaml
└── application/
    ├── api/  ...
    └── batch/  ...
```

### Dockerfile (parameterized)

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

**Notes:**
- `EXPOSE` bỏ — port khai báo ở compose `ports:` (api: 9000, batch: không expose).
- `ARG APP_MODULE` xuất hiện cả ở build stage và runtime stage (ARG không inherit cross-stage).
- `COPY application/${APP_MODULE}` cần BuildKit (Docker 23+; Compose v2 default).
- Khi `APP_MODULE=batch`, Maven `-am` resolve tự skip `security` dependency vì `application/batch/pom.xml` không khai. Build context có `security/` source nhưng compile bỏ qua — chấp nhận overhead nhỏ.

### Compose: postgres (dev infra)

`docker/postgres/docker-compose.dev.yaml`:

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

### Compose: api (dev)

`docker/api/docker-compose.dev.yaml`:

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

### Compose: api (staging) — giữ pattern hiện tại

`docker/api/docker-compose.staging.yaml`:

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

Production tương tự staging, chỉ thay `SPRING_PROFILES_ACTIVE: production` + `image: api:production`.

### Compose: batch (dev)

`docker/batch/docker-compose.dev.yaml`:

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

Batch không expose port (không phải web service). Không khai báo `JWT_SECRET` (batch không dùng security module).

### Compose: batch (staging)

`docker/batch/docker-compose.staging.yaml`:

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

Batch staging/production KHÔNG có `healthcheck` (chưa có endpoint health/liveness). Khi batch service thật được implement và expose endpoint kiểm tra, bổ sung healthcheck sau (out of scope refactor này).

Production thay `SPRING_PROFILES_ACTIVE: production` + `image: batch:production`.

### Dev workflow

```bash
# Bước 1: start postgres (tạo pms-net)
docker compose -f docker/postgres/docker-compose.dev.yaml up -d

# Bước 2: start api (join pms-net)
docker compose -f docker/api/docker-compose.dev.yaml up --build -d

# Optional: start batch (join cùng network)
docker compose -f docker/batch/docker-compose.dev.yaml up --build -d

# Health check
curl http://localhost:9000/api/v1/actuator/health

# Tear down (chú ý: postgres compose down -v xóa volume)
docker compose -f docker/batch/docker-compose.dev.yaml down
docker compose -f docker/api/docker-compose.dev.yaml down
docker compose -f docker/postgres/docker-compose.dev.yaml down -v
```

**Alias tiện lợi (optional)**: user có thể `export COMPOSE_FILE=docker/postgres/docker-compose.dev.yaml:docker/api/docker-compose.dev.yaml` rồi `docker compose up` chạy như cũ. Document trong `.env.example`.

### Staging/Production workflow

```bash
# Trên host staging
docker compose -f docker/api/docker-compose.staging.yaml up --build -d
docker compose -f docker/batch/docker-compose.staging.yaml up --build -d
```

Postgres external (managed), env vars truyền qua `.env` file hoặc CI/CD secrets.

## Migration impact

### Files thay đổi

| Action | File |
|---|---|
| Delete | `docker-compose.yaml` |
| Delete | `docker-compose.staging.yaml` |
| Delete | `docker-compose.production.yaml` |
| Move | `docker/postgres-init/` → `docker/postgres/init/` |
| Create | `docker/postgres/docker-compose.dev.yaml` |
| Create | `docker/api/docker-compose.{dev,staging,production}.yaml` |
| Create | `docker/batch/docker-compose.{dev,staging,production}.yaml` |
| Modify | `Dockerfile` (parameterized ARG APP_MODULE) |
| Modify | `CLAUDE.md` (lệnh dev mới, quy ước folder=artifactId) |
| Modify | `README.md` (cây thư mục, "How to run", "Deploy") |
| Modify | `.env.example` (thêm comment alias COMPOSE_FILE) |

### Breaking changes

1. **`docker compose up` ở root không còn chạy** (không còn `docker-compose.yaml` ở root). User phải dùng lệnh với `-f` rõ ràng, hoặc set `COMPOSE_FILE` env var.
2. **Container names đổi**: `api` → `pms-api`, `postgresDb` → `pms-postgres`. Scripts hoặc CI tham chiếu tên cũ phải update.
3. **Dev order phụ thuộc**: postgres compose phải start TRƯỚC api/batch compose (vì nó tạo external network `pms-net`).

### Out of scope

- Implement chức năng batch thực sự (refactor chỉ chuẩn bị Docker scaffold).
- Tách postgres compose cho staging/prod (staging/prod dùng DB external — không cần).
- CI/CD workflows.
- Wrapper script / Makefile.

## Verification

- [ ] `mvn clean install -pl -mybatis-generator,-mybatis-schema-migration` BUILD SUCCESS (không đụng Maven layer).
- [ ] `docker build --build-arg APP_MODULE=api -t api:test .` → success, image start được, `/actuator/health` 200.
- [ ] `docker build --build-arg APP_MODULE=batch -t batch:test .` → success, image start không lỗi (chỉ load context).
- [ ] Dev compose: postgres → api up, `curl /api/v1/actuator/health` trả 200.
- [ ] Dev compose: postgres → api → batch up đồng thời, cả 3 container UP, không xung đột network.
- [ ] Login flow qua api compose dev: `POST /api/v1/auth/login` với seed user trả token.
- [ ] `grep -rE 'docker-compose\.yaml|docker-compose\.staging|docker-compose\.production' README.md CLAUDE.md` rỗng sau update.

## Notes

- **Quy ước folder=artifactId**: nếu sau này cần đặt folder khác artifactId (vd refactor), Dockerfile sẽ broken. Thêm dòng nhắc trong CLAUDE.md mục "Coding rules" để tránh.
- **Khi cần app KHÔNG phải Spring Boot Java**: spec này không phục vụ. Khi gặp phải, tạo Dockerfile riêng `application/<app>/Dockerfile` và compose tự build context từ thư mục đó (không dùng root Dockerfile param). CLAUDE.md note rõ.
- **Cải tiến tương lai (nếu cần)**: Docker Compose v2.20+ có `include:` — có thể tạo `docker-compose.yaml` ở root mà `include:` postgres + api để giữ backward-compat 1-lệnh-dev. Hiện tại không làm vì user đã đồng ý dùng `-f` thẳng.

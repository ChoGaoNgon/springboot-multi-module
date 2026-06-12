# Staging/Production Compose + Multi-stage Dockerfile + Parameterized Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add environment-specific Docker Compose files for staging and production (external DB, secrets injected at run time), convert the Dockerfile to a slim non-root multi-stage build, and parameterize mybatis-schema-migration connection via a wrapper script.

**Architecture:** Two standalone compose files define only the `api` service and read all config from `${VAR}` (required secrets use `:?` to fail fast). The Dockerfile becomes build→runtime multi-stage (`eclipse-temurin:21-jre`, non-root, `curl` for healthcheck). Because `migrations-maven-plugin` 1.1.3 does NOT resolve `${...}` placeholders (verified), a `migrate.sh` wrapper generates the per-env `.properties` from environment variables via a bash heredoc, runs the migration, then deletes the file.

**Tech Stack:** Docker Compose v2, Docker multi-stage build, Spring Boot 3.3.5 / Java 21, mybatis-migrations 1.1.3, PostgreSQL 16, bash.

**Spec:** `docs/superpowers/specs/2026-06-13-staging-production-compose-design.md`. Branch: `feature/staging-prod-compose`.

**Build/verify env:** Java 21 (`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`), Docker running. The shell prints a large env block before real output — ignore it; real output is at the bottom.

---

## Key facts (verified)

- App is fully env-driven: `SPRING_PROFILES_ACTIVE`, `POSTGRES_HOST/PORT/DB/USER/PASSWORD`, `JWT_SECRET`, `LOG_PATH`, `PORT`. Context path is `/api/v1/`; actuator health at `/api/v1/actuator/health` is whitelisted (200 without token).
- Profiles `staging`/`production` already exist in `web/api/src/main/resources/application.yml`.
- `migrations-maven-plugin` 1.1.3 does **not** substitute `${...}` in env files (probed: literal `${POSTGRES_HOST}` reaches the JDBC driver). Hence the `migrate.sh` heredoc approach.
- `.gitignore` already exists (toptal template) and already ignores `target/`; append new patterns at the end.
- `staging.properties` and `production.properties` are git-tracked and must be `git rm`-ed.
- Dev compose `docker-compose.yaml` builds the same `Dockerfile` and tags `image: api:1.0.0`; it must keep working after the Dockerfile change (regression).

---

### Task 1: Multi-stage Dockerfile (slim, non-root)

**Files:**
- Modify: `Dockerfile` (replace entire file)

- [ ] **Step 1: Replace `Dockerfile` with the multi-stage build**

```dockerfile
# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy all module poms first so dependency resolution is a cacheable layer
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

# Copy sources and build the api jar
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

- [ ] **Step 2: Build + run the dev stack (regression) and wait for health**

The dev compose builds this Dockerfile and tags `api:1.0.0`.

Run:
```bash
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij docker compose down -v
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij docker compose up --build -d
curl -s --retry 40 --retry-delay 2 --retry-connrefused -o /dev/null -w '%{http_code}\n' \
  http://localhost:9000/api/v1/actuator/health
```
Expected: build succeeds; final line prints `200`.

> If the build fails at the `dependency:go-offline` line (rare — a plugin not resolvable fully offline), delete that single `RUN mvn -am -pl web/api -DskipTests dependency:go-offline` line from the Dockerfile. The later `package` step still downloads whatever is missing; only build-layer caching efficiency is lost.

- [ ] **Step 3: Verify login still works (end-to-end regression through the new image)**

Run:
```bash
curl -s -X POST http://localhost:9000/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | head -c 80; echo
```
Expected: a JSON containing `"accessToken":"..."`.

- [ ] **Step 4: Verify the runtime image is non-root and slimmer**

Run:
```bash
docker run --rm --entrypoint id api:1.0.0 -u
docker images api:1.0.0 --format '{{.Size}}'
```
Expected: `id -u` prints a non-zero uid (the `app` user, not `0`); size is a few hundred MB (substantially smaller than the previous fat image — sanity check, no hard threshold).

- [ ] **Step 5: Tear down the dev stack**

Run: `docker compose down -v`
Expected: containers + volume removed.

- [ ] **Step 6: Commit**

```bash
git add Dockerfile
git commit -m "build(docker): multi-stage Dockerfile (jre runtime, non-root, curl)"
```

---

### Task 2: Staging & production compose files

**Files:**
- Create: `docker-compose.staging.yaml`
- Create: `docker-compose.production.yaml`

- [ ] **Step 1: Create `docker-compose.production.yaml`**

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

- [ ] **Step 2: Create `docker-compose.staging.yaml`**

Identical to production except `SPRING_PROFILES_ACTIVE`, `image`, and the `MEM_LIMIT` default:

```yaml
services:
  api:
    build:
      context: ./
      dockerfile: Dockerfile
    image: api:staging
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

- [ ] **Step 3: Verify both files parse when secrets are provided**

Run:
```bash
POSTGRES_HOST=db POSTGRES_DB=app POSTGRES_USER=u POSTGRES_PASSWORD=p JWT_SECRET=s \
  docker compose -f docker-compose.production.yaml config >/dev/null && echo "production OK"
POSTGRES_HOST=db POSTGRES_DB=app POSTGRES_USER=u POSTGRES_PASSWORD=p JWT_SECRET=s \
  docker compose -f docker-compose.staging.yaml config >/dev/null && echo "staging OK"
```
Expected: `production OK` and `staging OK`.

- [ ] **Step 4: Verify a missing secret fails fast**

Run (JWT_SECRET intentionally unset):
```bash
POSTGRES_HOST=db POSTGRES_DB=app POSTGRES_USER=u POSTGRES_PASSWORD=p \
  docker compose -f docker-compose.production.yaml config 2>&1 | grep -i "JWT_SECRET is required" && echo "fail-fast OK"
```
Expected: prints the `JWT_SECRET is required` error and `fail-fast OK`.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.staging.yaml docker-compose.production.yaml
git commit -m "feat(docker): staging + production compose (api only, external DB, secrets injected)"
```

---

### Task 3: `.env.example` + `.gitignore`

**Files:**
- Create: `.env.example`
- Modify: `.gitignore` (append)

- [ ] **Step 1: Create `.env.example`**

```dotenv
# Copy to .env.staging / .env.production and fill in real values (DO NOT commit).
# Run: docker compose --env-file .env.production -f docker-compose.production.yaml up -d --build

# --- External database (REQUIRED) ---
POSTGRES_HOST=
POSTGRES_PORT=5432
POSTGRES_DB=
POSTGRES_USER=
POSTGRES_PASSWORD=

# --- Security (REQUIRED) ---
# Random string >= 32 bytes for HS256
JWT_SECRET=

# --- Optional (have defaults in compose) ---
APP_PORT=9000
LOG_PATH=/var/log/app
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75
CPU_LIMIT=1
MEM_LIMIT=1g
```

- [ ] **Step 2: Append secret-file patterns to `.gitignore`**

Append these lines to the end of the existing `.gitignore`:

```gitignore

# --- Secret env files (real values, never commit) ---
.env
.env.*
!.env.example

# Migration env files generated at run time by migrate.sh (contain secrets)
mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/staging.properties
mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/production.properties
```

- [ ] **Step 3: Verify ignore rules behave correctly**

Run:
```bash
git check-ignore -v .env.production \
  mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/production.properties
git check-ignore .env.example && echo "BUG: .env.example is ignored" || echo ".env.example tracked OK"
```
Expected: the first command prints a match line for each of the two paths (they ARE ignored); the second prints `.env.example tracked OK`.

- [ ] **Step 4: Commit**

```bash
git add .env.example .gitignore
git commit -m "chore: .env.example template + gitignore secret env files"
```

---

### Task 4: `migrate.sh` wrapper + remove hardcoded env files

**Files:**
- Create: `mybatis-schema-migration/migrate.sh`
- Delete: `mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/staging.properties`
- Delete: `mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/production.properties`

- [ ] **Step 1: Create `mybatis-schema-migration/migrate.sh`**

```bash
#!/usr/bin/env bash
# Run mybatis-migrations with connection injected via environment variables
# (no hardcoded secrets). The per-env .properties file is generated from env
# vars, used, and then removed (no secret left on disk).
#
# Usage: POSTGRES_HOST=.. POSTGRES_DB=.. POSTGRES_USER=.. POSTGRES_PASSWORD=.. \
#          ./migrate.sh <env> <command> [extra mvn args...]
# Examples:
#   ./migrate.sh production up
#   ./migrate.sh staging status
#   ./migrate.sh production down -Dmigration.down.steps=1
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

- [ ] **Step 2: Make it executable + remove the hardcoded env files**

Run:
```bash
chmod +x mybatis-schema-migration/migrate.sh
git rm mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/staging.properties \
       mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/production.properties
```
Expected: both files staged for deletion.

- [ ] **Step 3: Start a throwaway Postgres that plays the role of the external DB**

Run:
```bash
docker run -d --name mig-test-db -e POSTGRES_PASSWORD=testpw -e POSTGRES_DB=testdb \
  -p 55432:5432 postgres:16
# wait until it accepts connections
until docker exec mig-test-db pg_isready -U postgres -d testdb >/dev/null 2>&1; do sleep 1; done
echo "mig-test-db ready"
```
Expected: prints `mig-test-db ready`.

- [ ] **Step 4: Run the migration via the wrapper and confirm tables + cleanup**

Run:
```bash
export POSTGRES_HOST=localhost POSTGRES_PORT=55432 POSTGRES_DB=testdb \
       POSTGRES_USER=postgres POSTGRES_PASSWORD=testpw
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./mybatis-schema-migration/migrate.sh staging up
docker exec mig-test-db psql -U postgres -d testdb -c "\dt" | grep -E "users|roles|permissions" && echo "TABLES OK"
git status --short mybatis-schema-migration/src/main/resources/co/jp/htkk/migration/environments/
```
Expected: migration applies (`...up` success); `TABLES OK` printed (users/roles/permissions exist); the `git status` line shows NO `staging.properties` (the generated file was removed by the trap — only the two deletions from Step 2 remain).

- [ ] **Step 5: Tear down the throwaway DB**

Run: `docker rm -f mig-test-db && unset POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD`
Expected: container removed.

- [ ] **Step 6: Commit**

```bash
git add mybatis-schema-migration/migrate.sh
git commit -m "feat(migration): migrate.sh injects DB connection from env; drop hardcoded staging/prod env files"
```

---

### Task 5: README — staging/production + migration instructions

**Files:**
- Modify: `README.md` (append a new top-level section at the end)

- [ ] **Step 1: Append the operations section to `README.md`**

Append at the very end of `README.md`:

```markdown
## How to run on Staging / Production (Docker Compose)

Staging and production use an **external** PostgreSQL (no DB container) and **no init script** — schema is applied by `mybatis-schema-migration`. Images are built in place from the multi-stage `Dockerfile` (slim JRE runtime, non-root). All secrets are injected at run time.

### 1. Apply the database schema (prerequisite)

The `migrate.sh` wrapper injects the connection from environment variables (the plugin does not resolve `${...}` placeholders). It generates the env file, runs the migration, then deletes it.

```bash
export POSTGRES_HOST=db.internal POSTGRES_PORT=5432 POSTGRES_DB=app \
       POSTGRES_USER=app_user POSTGRES_PASSWORD='********'

./mybatis-schema-migration/migrate.sh production status                      # show status
./mybatis-schema-migration/migrate.sh production up                          # apply pending migrations
./mybatis-schema-migration/migrate.sh production down -Dmigration.down.steps=1   # rollback one step
```
(Requires JDK 21 on `PATH`/`JAVA_HOME`. Use `staging` for the staging DB. `development`/`local` still run directly, e.g. `mvn -pl mybatis-schema-migration migration:up -Dmigration.env=local`.)

### 2. Run the API

Required env vars: `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET` (>= 32 bytes). See `.env.example`. Missing any required var makes compose fail immediately.

```bash
# Copy the template and fill in real values (file is gitignored)
cp .env.example .env.production

docker compose --env-file .env.production -f docker-compose.production.yaml up -d --build
# staging:
docker compose --env-file .env.staging -f docker-compose.staging.yaml up -d --build
```

Health check (whitelisted, no token): `curl http://<host>:9000/api/v1/actuator/health` → `{"status":"UP"}`.
```

- [ ] **Step 2: Verify the section renders / file is valid markdown**

Run: `tail -n 5 README.md`
Expected: shows the new health-check line at the end of the file.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: README — staging/production compose run + parameterized migration"
```

---

### Task 6: Final integrated verification

This exercises the whole staging path: external DB → migration → staging compose app boot.

- [ ] **Step 1: Start a throwaway Postgres as the external DB**

Run:
```bash
docker run -d --name stg-db -e POSTGRES_PASSWORD=stgpw -e POSTGRES_DB=stgdb -p 55432:5432 postgres:16
until docker exec stg-db pg_isready -U postgres -d stgdb >/dev/null 2>&1; do sleep 1; done
echo "stg-db ready"
```
Expected: `stg-db ready`.

- [ ] **Step 2: Apply schema + bring up the staging app against that DB (single shell block)**

Env vars do NOT persist between separate shell invocations, so run this as ONE block:
```bash
export POSTGRES_PORT=55432 POSTGRES_DB=stgdb POSTGRES_USER=postgres POSTGRES_PASSWORD=stgpw \
       JWT_SECRET=staging-secret-0123456789-0123456789
# migrate.sh runs on the HOST -> reach the published DB via localhost
POSTGRES_HOST=localhost JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./mybatis-schema-migration/migrate.sh staging up
# the app runs in a CONTAINER -> reach the host-published DB via host.docker.internal
export POSTGRES_HOST=host.docker.internal
docker compose -f docker-compose.staging.yaml up -d --build
curl -s --retry 40 --retry-delay 2 --retry-connrefused -o /dev/null -w '%{http_code}\n' \
  http://localhost:9000/api/v1/actuator/health
```
Expected: migration `up` succeeds; the final line prints `200`. (`/actuator/health` aggregates the `db` health indicator, so `200`=UP proves the containerized app actually reached the external DB.)

- [ ] **Step 3: Tear everything down**

Run:
```bash
docker compose -f docker-compose.staging.yaml down
docker rm -f stg-db
```
Expected: api container + stg-db removed.

- [ ] **Step 4: Confirm a clean tree + no leftover secret files**

Run: `git status`
Expected: working tree clean (no generated `staging.properties`/`production.properties`, no `.env.*`).

---

## Verification Summary (maps to spec §Verification)

1. Both compose files parse with secrets set; missing secret fails fast. ✔ Task 2.3–2.4
2. Multi-stage image builds, runs non-root, is slimmer. ✔ Task 1.4
3. Dev compose still works with the new Dockerfile (login/health). ✔ Task 1.2–1.3
4. Migration runs against an external DB via `migrate.sh` (resolved host, file cleaned up), then the staging app boots against that DB with health 200. ✔ Task 4.4, Task 6

## Out of scope

- CI/CD pipeline, image registry, reverse proxy/TLS, k8s/swarm.
- A migration service inside compose.

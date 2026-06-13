# Application Module Aggregator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gom 2 module tầng application (`web/api`, `batch`) vào 1 aggregator POM `application/` để shared deps một chỗ và thêm app mới dễ dàng — không phá artifactId, JAR name, profile, env.

**Architecture:** Maven aggregator pattern. Tạo `application/pom.xml` (packaging=pom) chứa shared deps + spring-boot-maven-plugin. Move `web/api/` → `application/api/` và `batch/` → `application/batch/` (giữ artifactId cũ). Xóa `web/pom.xml` cũ. Cập nhật root pom, Dockerfile, docs theo path mới.

**Tech Stack:** Maven 3.9 (multi-module), Spring Boot 3.3.5, JDK 21, Docker multi-stage build.

**Spec:** `docs/superpowers/specs/2026-06-13-application-module-aggregator-design.md`

**Branch:** `refactor/application-module-aggregator` (đã có commit spec).

**Prerequisites:**
- Đang ở branch `refactor/application-module-aggregator`.
- `git status` sạch.
- Có `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` set sẵn (hoặc shell tự có JDK 21).

---

## Task 1: Create `application/pom.xml` and move `web/api/` → `application/api/`

**Files:**
- Create: `application/pom.xml`
- Move (`git mv`): `web/api/` → `application/api/`
- Modify: `application/api/pom.xml` (đổi parent + thêm AOP)
- Modify: `pom.xml` (root: bỏ `<module>web</module>`, thêm `<module>application</module>`)
- Delete: `web/pom.xml`, thư mục `web/`

### - [ ] Step 1: Verify clean state

Run:
```bash
git status
git rev-parse --abbrev-ref HEAD
```
Expected: working tree clean, branch `refactor/application-module-aggregator`. Nếu KHÔNG sạch → stop, hỏi user.

### - [ ] Step 2: Move `web/api/` → `application/api/`

Run:
```bash
mkdir -p application
git mv web/api application/api
```
Expected: lệnh không lỗi. `web/` chỉ còn `pom.xml` (và có thể `target/` untracked).

### - [ ] Step 3: Create `application/pom.xml`

Content (LƯU Ý: chỉ list `api` ở `<modules>`, batch sẽ thêm ở Task 2):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>jp.co.htkk</groupId>
        <artifactId>point-management-sys</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>application</artifactId>
    <packaging>pom</packaging>

    <modules>
        <module>api</module>
    </modules>

    <properties>
        <appName>${project.artifactId}-${project.version}</appName>
    </properties>

    <dependencies>
        <!-- Business module — cả api và batch đều cần -->
        <dependency>
            <groupId>jp.co.htkk</groupId>
            <artifactId>business-implementation</artifactId>
        </dependency>
    </dependencies>

    <build>
        <finalName>${appName}</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

    <pluginRepositories>
        <pluginRepository>
            <id>spring-snapshots</id>
            <url>https://repo.spring.io/snapshot</url>
        </pluginRepository>
        <pluginRepository>
            <id>spring-milestones</id>
            <url>https://repo.spring.io/milestone</url>
        </pluginRepository>
    </pluginRepositories>
</project>
```

### - [ ] Step 4: Edit `application/api/pom.xml` — đổi parent + thêm AOP

Đổi `<parent><artifactId>web</artifactId></parent>` thành `<artifactId>application</artifactId>` và thêm `spring-boot-starter-aop` (vì giờ không inherit từ `web` parent nữa).

Replace block:
```xml
    <parent>
        <groupId>jp.co.htkk</groupId>
        <artifactId>web</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>api</artifactId>

    <name>api</name>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <dependency>
            <groupId>jp.co.htkk</groupId>
            <artifactId>security</artifactId>
        </dependency>

        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>

    </dependencies>
```

With:
```xml
    <parent>
        <groupId>jp.co.htkk</groupId>
        <artifactId>application</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>api</artifactId>

    <name>api</name>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>

        <dependency>
            <groupId>jp.co.htkk</groupId>
            <artifactId>security</artifactId>
        </dependency>

        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>

    </dependencies>
```

### - [ ] Step 5: Edit root `pom.xml` — bỏ `web`, thêm `application` (giữ `batch`)

Trong block `<modules>` của root `pom.xml`, thay:
```xml
        <module>web</module>
        <module>batch</module>
```
Bằng:
```xml
        <module>application</module>
        <module>batch</module>
```

(Batch vẫn ở vị trí cũ, sẽ move ở Task 2.)

### - [ ] Step 6: Xóa `web/pom.xml` và thư mục `web/`

Run:
```bash
git rm web/pom.xml
rm -rf web
```
Expected: `web/pom.xml` được stage để xóa. Thư mục `web/` (cùng `target/` untracked nếu có) biến mất.

### - [ ] Step 7: Verify build API mới

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -am -pl application/api clean install -DskipTests -q
```
Expected: `BUILD SUCCESS`. File `application/api/target/api-0.0.1-SNAPSHOT.jar` xuất hiện.

Nếu fail vì `ClassNotFoundException Aspect`: kiểm tra Step 4 đã thêm `spring-boot-starter-aop` chưa.

### - [ ] Step 8: Verify integration tests API

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -am -pl application/api test
```
Expected: `BUILD SUCCESS`, các integration test (UserCrudIntegrationTest, etc.) chạy được trên H2.

### - [ ] Step 9: Commit

Run:
```bash
git add application/ pom.xml
git status   # đảm bảo `web/pom.xml` ở state "deleted", `web/api/...` ở state "renamed to application/api/..."
git commit -m "refactor(modules): create application/ aggregator + move web/api → application/api

- application/pom.xml (new): parent POM cho tầng application, chứa shared
  deps (business-implementation) + spring-boot-maven-plugin
- application/api/: move từ web/api/ (git mv giữ history), parent đổi
  thành 'application', thêm spring-boot-starter-aop (trước đây inherit
  từ web/ parent)
- root pom.xml: <module>web</module> → <module>application</module>
- web/pom.xml: xóa (nội dung gộp lên application/pom.xml)
- batch vẫn ở vị trí cũ, sẽ move ở task sau"
```

---

## Task 2: Move `batch/` → `application/batch/`

**Files:**
- Move (`git mv`): `batch/` → `application/batch/`
- Modify: `application/batch/pom.xml` (đổi parent + bỏ deps đã chung)
- Modify: `application/pom.xml` (thêm `<module>batch</module>`)
- Modify: `pom.xml` (root: bỏ `<module>batch</module>`)

### - [ ] Step 1: Move `batch/` → `application/batch/`

Run:
```bash
git mv batch application/batch
```
Expected: không lỗi. `batch/` không còn ở root.

### - [ ] Step 2: Edit `application/batch/pom.xml` — đổi parent + bỏ deps đã chung

Replace block:
```xml
    <parent>
        <groupId>jp.co.htkk</groupId>
        <artifactId>point-management-sys</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>batch</artifactId>
    <name>batch</name>

    <properties>
        <appName>${project.artifactId}-${project.version}</appName>
        <xlsx.streamer.version>2.1.0</xlsx.streamer.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <exclusions>
                <exclusion>
                    <artifactId>tomcat-embed-el</artifactId>
                    <groupId>org.apache.tomcat.embed</groupId>
                </exclusion>
                <exclusion>
                    <artifactId>tomcat-embed-core</artifactId>
                    <groupId>org.apache.tomcat.embed</groupId>
                </exclusion>
                <exclusion>
                    <artifactId>tomcat-embed-websocket</artifactId>
                    <groupId>org.apache.tomcat.embed</groupId>
                </exclusion>
            </exclusions>
        </dependency>

        <!-- Business module -->
        <dependency>
            <groupId>jp.co.htkk</groupId>
            <artifactId>business-implementation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-devtools</artifactId>
            <scope>runtime</scope>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.monitorjbl</groupId>
            <artifactId>xlsx-streamer</artifactId>
            <version>${xlsx.streamer.version}</version>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>${appName}</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
```

With (BỎ: `business-implementation`, `lombok`, property `appName`, `<build>` section, `<finalName>`, `spring-boot-maven-plugin` — tất cả đã inherit từ parent `application`):

```xml
    <parent>
        <groupId>jp.co.htkk</groupId>
        <artifactId>application</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>batch</artifactId>
    <name>batch</name>

    <properties>
        <xlsx.streamer.version>2.1.0</xlsx.streamer.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <exclusions>
                <exclusion>
                    <artifactId>tomcat-embed-el</artifactId>
                    <groupId>org.apache.tomcat.embed</groupId>
                </exclusion>
                <exclusion>
                    <artifactId>tomcat-embed-core</artifactId>
                    <groupId>org.apache.tomcat.embed</groupId>
                </exclusion>
                <exclusion>
                    <artifactId>tomcat-embed-websocket</artifactId>
                    <groupId>org.apache.tomcat.embed</groupId>
                </exclusion>
            </exclusions>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-devtools</artifactId>
            <scope>runtime</scope>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.monitorjbl</groupId>
            <artifactId>xlsx-streamer</artifactId>
            <version>${xlsx.streamer.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

### - [ ] Step 3: Edit `application/pom.xml` — thêm `<module>batch</module>`

Trong block `<modules>` của `application/pom.xml`, thay:
```xml
    <modules>
        <module>api</module>
    </modules>
```
Bằng:
```xml
    <modules>
        <module>api</module>
        <module>batch</module>
    </modules>
```

### - [ ] Step 4: Edit root `pom.xml` — bỏ `<module>batch</module>`

Trong block `<modules>` của root, xóa dòng `<module>batch</module>` (giữ `<module>application</module>` đã thêm ở Task 1).

Sau Step 4, block `<modules>` của root nên có:
```xml
    <modules>
        <module>framework</module>
        <module>security</module>
        <module>dto</module>
        <module>entity</module>
        <module>persistence</module>
        <module>business</module>
        <module>mybatis-generator</module>
        <module>mybatis-schema-migration</module>
        <module>application</module>
    </modules>
```

### - [ ] Step 5: Verify build batch mới

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -am -pl application/batch clean install -DskipTests -q
```
Expected: `BUILD SUCCESS`. File `application/batch/target/batch-0.0.1-SNAPSHOT.jar` xuất hiện.

Nếu fail vì missing `business-implementation`: kiểm tra Step 2 — parent đã đổi sang `application` chưa (parent inject dep business-implementation cho cả 2 sub).

### - [ ] Step 6: Verify full build (chỉ exclude tooling)

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration -q
```
Expected: `BUILD SUCCESS`. Tất cả module reactor build (framework → security → ... → application → application/api → application/batch). Integration tests trên H2 pass.

### - [ ] Step 7: Commit

Run:
```bash
git add application/ pom.xml
git status   # đảm bảo batch/* show as renamed to application/batch/*
git commit -m "refactor(modules): move batch → application/batch + dedupe deps

- application/batch/: move từ batch/ (git mv giữ history)
- application/batch/pom.xml: parent đổi sang 'application'. Bỏ
  business-implementation, lombok, appName property, finalName,
  spring-boot-maven-plugin — đã inherit từ parent application/
- application/pom.xml: thêm <module>batch</module>
- root pom.xml: bỏ <module>batch</module> (đã subsumed bởi application)"
```

---

## Task 3: Cập nhật `Dockerfile`

**Files:**
- Modify: `Dockerfile` (rename `web/` path → `application/`, đổi `-pl web/api` → `-pl application/api`, đổi JAR copy path)

### - [ ] Step 1: Replace dependency-cache layer block

Trong `Dockerfile`, thay:
```dockerfile
COPY web/pom.xml web/pom.xml
COPY web/api/pom.xml web/api/pom.xml
COPY mybatis-generator/pom.xml mybatis-generator/pom.xml
COPY mybatis-schema-migration/pom.xml mybatis-schema-migration/pom.xml
COPY batch/pom.xml batch/pom.xml
RUN mvn -am -pl web/api -DskipTests dependency:go-offline
```

Bằng:
```dockerfile
COPY application/pom.xml application/pom.xml
COPY application/api/pom.xml application/api/pom.xml
COPY application/batch/pom.xml application/batch/pom.xml
COPY mybatis-generator/pom.xml mybatis-generator/pom.xml
COPY mybatis-schema-migration/pom.xml mybatis-schema-migration/pom.xml
RUN mvn -am -pl application/api -DskipTests dependency:go-offline
```

### - [ ] Step 2: Replace sources copy + build block

Trong `Dockerfile`, thay:
```dockerfile
COPY web web
RUN mvn -am -pl web/api clean package -DskipTests
```

Bằng:
```dockerfile
COPY application/api application/api
RUN mvn -am -pl application/api clean package -DskipTests
```

Chỉ copy `application/api` source — không copy `application/batch` vì image runtime không chạy batch. `application/pom.xml` đã được COPY ở layer pom-cache phía trên (Step 1), không cần copy lại — `COPY application/api` chỉ copy nội dung sub-module `api/`, không động đến `application/pom.xml` parent.

### - [ ] Step 3: Replace JAR copy ở runtime stage

Trong `Dockerfile`, thay:
```dockerfile
COPY --from=build /app/web/api/target/api-0.0.1-SNAPSHOT.jar app.jar
```

Bằng:
```dockerfile
COPY --from=build /app/application/api/target/api-0.0.1-SNAPSHOT.jar app.jar
```

### - [ ] Step 4: Verify `docker build` thành công

Run:
```bash
docker build -t pms-api-refactor-test .
```
Expected: `Successfully tagged pms-api-refactor-test:latest`. Build cache cho dependency resolution layer hoạt động (lần thứ 2 chạy lại nhanh).

Nếu fail ở stage maven do reactor missing module: kiểm tra cả 3 file pom (application/pom.xml, application/api/pom.xml, application/batch/pom.xml) đã được COPY trước khi `mvn dependency:go-offline`.

### - [ ] Step 5: Commit

Run:
```bash
git add Dockerfile
git commit -m "build(docker): rename web/ paths → application/ in Dockerfile

- COPY web/{,api/}pom.xml → COPY application/{,api/,batch/}pom.xml
- mvn -pl web/api → mvn -pl application/api
- COPY --from=build /app/web/api/target/... → application/api/target/..."
```

---

## Task 4: Cập nhật `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md` (bảng module map + lệnh build)

### - [ ] Step 1: Đọc CLAUDE.md để xác định block cần sửa

Run:
```bash
grep -n -E 'web/api|web →|module>web|application' CLAUDE.md
```
Expected: liệt kê dòng nhắc đến `web/api`, module map, lệnh build.

### - [ ] Step 2: Cập nhật mục "Lệnh build / test / run"

Replace block:
```markdown
```bash
# Build + test toàn bộ (LOẠI TRỪ 2 module tooling vì chúng cần DB sống để build)
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration

# Chỉ test web/api (integration test trên H2)
JAVA_HOME=... mvn test -pl web/api
```

With:
```markdown
```bash
# Build + test toàn bộ (LOẠI TRỪ 2 module tooling vì chúng cần DB sống để build)
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration

# Chỉ test application/api (integration test trên H2)
JAVA_HOME=... mvn test -pl application/api
```

### - [ ] Step 3: Cập nhật mục "Module map (thứ tự build & chiều phụ thuộc)"

Replace block:
```
framework  → security → dto → entity → persistence → business → web/api → batch
                                                       (+ mybatis-generator, mybatis-schema-migration: tooling)
```

With:
```
framework  → security → dto → entity → persistence → business → application/{api,batch}
                                                       (+ mybatis-generator, mybatis-schema-migration: tooling)
```

### - [ ] Step 4: Cập nhật bảng module — đổi `web/api` → `application/api`, thêm hàng `application`

Trong bảng module, thay 2 hàng:
```markdown
| `web/api` | App chính (`PointManagementSysApplication`), controller, config, exception advice, adapter security. |
| `batch` | Batch job độc lập (KHÔNG khai dependency `security`). |
```

Bằng 3 hàng (thêm `application` aggregator):
```markdown
| `application` | Aggregator POM (packaging=pom) gom các app deployable. Shared deps (`business-implementation`, `spring-boot-maven-plugin`) khai 1 chỗ. Thêm app mới: tạo `application/<tên>/`. |
| `application/api` | App chính (`PointManagementSysApplication`), controller, config, exception advice, adapter security. |
| `application/batch` | Batch job độc lập (KHÔNG khai dependency `security`). |
```

### - [ ] Step 5: Cập nhật mục "MyBatis" — đổi tham chiếu file XML/Java

Search-replace bằng tay (KHÔNG dùng `replace_all` vì chỉ đổi nếu rõ context):
```bash
grep -n 'web/api' CLAUDE.md
```

Mỗi dòng còn lại match (vd `MyBatisConfig` (web/api)`) → đổi `(web/api)` thành `(application/api)`.

### - [ ] Step 6: Verify không còn dòng `web/api` trong CLAUDE.md

Run:
```bash
grep -n 'web/api' CLAUDE.md
```
Expected: rỗng.

### - [ ] Step 7: Commit

Run:
```bash
git add CLAUDE.md
git commit -m "docs(CLAUDE): update module map for application/ aggregator

- module map: web/api → application/{api,batch}
- bảng module: thêm hàng application (aggregator), đổi web/api → application/api, batch → application/batch
- lệnh build: -pl web/api → -pl application/api"
```

---

## Task 5: Cập nhật `README.md`

**Files:**
- Modify: `README.md` (cây thư mục + bảng module)

### - [ ] Step 1: Đọc các section cần sửa

Run:
```bash
grep -n -E 'web/|web/api|batch/|application' README.md
```
Expected: liệt kê các điểm match (kiến trúc, cây thư mục, bảng module).

### - [ ] Step 2: Cập nhật biểu đồ "Architecture — multi-module"

Replace block:
```
framework → security → dto → entity → persistence → business → web/api → batch
                                          (+ mybatis-generator, mybatis-schema-migration: tooling)
```

With:
```
framework → security → dto → entity → persistence → business → application/{api,batch}
                                          (+ mybatis-generator, mybatis-schema-migration: tooling)
```

### - [ ] Step 3: Cập nhật cây thư mục ASCII

Trong block tree ```, replace:
```
├── web/                            # ── module chính ──
│   └── api/                        # PointManagementSysApplication, controller/admin/UserController,
│                                   #   config(MyBatisConfig, WebMvcConfiguration, SpringdocConfig),
│                                   #   exception/ExceptionControllerAdvice, security/SecurityUserServiceImpl
├── batch/                          # batch job độc lập (không dùng security)
├── mybatis-generator/              # tooling: generate entity/dao
├── mybatis-schema-migration/       # tooling: DDL migration + migrate.sh
```

With:
```
├── application/                    # ── tầng application: aggregator POM ──
│   ├── api/                        # PointManagementSysApplication, controller/admin/UserController,
│   │                               #   config(MyBatisConfig, WebMvcConfiguration, SpringdocConfig),
│   │                               #   exception/ExceptionControllerAdvice, security/SecurityUserServiceImpl
│   └── batch/                      # batch job độc lập (không dùng security)
├── mybatis-generator/              # tooling: generate entity/dao
├── mybatis-schema-migration/       # tooling: DDL migration + migrate.sh
```

### - [ ] Step 4: Cập nhật bảng "Module"

Replace 2 hàng:
```markdown
| **web/api** | App chính: REST controller, config, exception advice, adapter security. |
| **batch** | Scheduled batch job. |
```

With 3 hàng:
```markdown
| **application** | Aggregator (packaging=pom) gom các app deployable; shared deps khai 1 chỗ. |
| **application/api** | App chính: REST controller, config, exception advice, adapter security. |
| **application/batch** | Scheduled batch job. |
```

### - [ ] Step 5: Cập nhật mục "How to build"

Replace:
```bash
mvn test -pl web/api
```
With:
```bash
mvn test -pl application/api
```

### - [ ] Step 6: Verify không còn dòng `web/` hoặc `web/api` trong README.md

Run:
```bash
grep -n -E '\bweb/|`web/api`' README.md
```
Expected: rỗng (hoặc chỉ còn match không liên quan như URL).

### - [ ] Step 7: Commit

Run:
```bash
git add README.md
git commit -m "docs(README): update module tree for application/ aggregator

- biểu đồ dep flow: web/api → application/{api,batch}
- cây thư mục: web/api + batch/ → application/{api,batch}
- bảng module: thêm hàng application aggregator
- How to build: mvn test -pl web/api → -pl application/api"
```

---

## Task 6: Final verification (sanity check toàn bộ)

**Files:** không sửa file, chỉ chạy verify.

### - [ ] Step 1: Build full reactor (exclude tooling)

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn clean install -pl -mybatis-generator,-mybatis-schema-migration
```
Expected: `BUILD SUCCESS`. Reactor list:
- jp.co.htkk:point-management-sys
- framework, security, dto, entity, persistence
- business (+ business-interface, business-implementation)
- application (+ api, batch)

### - [ ] Step 2: Kiểm tra JAR output đúng tên + đúng path

Run:
```bash
ls -la application/api/target/api-0.0.1-SNAPSHOT.jar
ls -la application/batch/target/batch-0.0.1-SNAPSHOT.jar
```
Expected: cả 2 file tồn tại, kích thước > 1MB (fat JAR).

### - [ ] Step 3: Verify integration test riêng `application/api`

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn test -pl application/api
```
Expected: `BUILD SUCCESS`. Test `UserCrudIntegrationTest` + các test login/401/403 pass.

### - [ ] Step 4: Verify dev docker-compose build + up

Run:
```bash
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij \
  docker compose up --build -d
# đợi ~30s, rồi:
curl -fsS http://localhost:9000/api/v1/actuator/health
```
Expected: response `{"status":"UP"}`. Sau khi xác nhận:
```bash
docker compose down -v
```

### - [ ] Step 5: Verify không còn tham chiếu `web/` cũ trong file tracked

Run:
```bash
grep -rn -E '\bweb/api|web/pom|<module>web</module>' \
  --include='*.md' --include='*.xml' --include='Dockerfile' \
  --include='*.yml' --include='*.yaml' . 2>/dev/null \
  | grep -v -E '^\./(target|application/api/target|application/batch/target)/' \
  | grep -v 'docs/superpowers/specs\|docs/superpowers/plans'
```
Expected: rỗng (specs và plans cũ có thể nhắc đến cho lịch sử, OK).

### - [ ] Step 6: (Không commit) Verify checklist từ spec

Đối chiếu mục "Verification" trong `docs/superpowers/specs/2026-06-13-application-module-aggregator-design.md`:
- [x] `mvn clean install -pl -mybatis-generator,-mybatis-schema-migration` BUILD SUCCESS
- [x] `mvn test -pl application/api` integration test trên H2 pass
- [x] `docker compose up --build -d` (dev) → API up, health 200
- [x] `application/api/target/api-0.0.1-SNAPSHOT.jar` tồn tại
- [x] `application/batch/target/batch-0.0.1-SNAPSHOT.jar` tồn tại
- [x] `grep -rE 'web/api|web/pom' Dockerfile CLAUDE.md README.md` rỗng

Nếu mọi item check pass → refactor xong.

### - [ ] Step 7: Push branch + tạo PR

Run:
```bash
git push -u origin refactor/application-module-aggregator
gh pr create --title "refactor: gom web/api + batch vào application/ aggregator" --body "$(cat <<'EOF'
## Summary
- Tạo Maven aggregator POM `application/` chứa `application/api` (move từ `web/api`) và `application/batch` (move từ `batch`).
- Shared deps (`business-implementation`, `spring-boot-maven-plugin`) gom 1 chỗ ở `application/pom.xml`.
- `spring-boot-starter-aop` push DOWN từ parent cũ `web/` xuống `application/api/` vì batch không dùng.
- ArtifactId (`api`, `batch`), tên JAR, Spring profile, env vars, docker-compose: KHÔNG đổi.

Spec: `docs/superpowers/specs/2026-06-13-application-module-aggregator-design.md`
Plan: `docs/superpowers/plans/2026-06-13-application-module-aggregator.md`

## Test plan
- [ ] `mvn clean install -pl -mybatis-generator,-mybatis-schema-migration` → BUILD SUCCESS
- [ ] `mvn test -pl application/api` → integration test (H2) pass
- [ ] `docker build .` → image build OK
- [ ] `docker compose up --build -d` → `curl /actuator/health` trả 200
- [ ] `application/api/target/api-0.0.1-SNAPSHOT.jar` + `application/batch/target/batch-0.0.1-SNAPSHOT.jar` tồn tại sau build
EOF
)"
```
Expected: PR được tạo. URL được in ra.

---

## Notes về workflow cho engineer thực thi

- **`git mv` có thể fail nếu thư mục destination đã tồn tại với content**. Trước Step 2/Task 1 và Step 1/Task 2, đảm bảo `application/api/` và `application/batch/` chưa tồn tại.
- **IntelliJ users**: sau khi pull / checkout branch này, dùng `File > Invalidate Caches and Restart` hoặc right-click `pom.xml` root → `Maven > Reload Project` để IDE nhận module map mới.
- **Không skip step "verify build"** giữa các task — nếu Task 1 build OK nhưng Task 2 fail, biết ngay là do batch pom edit, không phải snowball lỗi.
- **Nếu cần rollback giữa chừng**: `git reset --hard HEAD~1` (mỗi task = 1 commit, rollback dễ).

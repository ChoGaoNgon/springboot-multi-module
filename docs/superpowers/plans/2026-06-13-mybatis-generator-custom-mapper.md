# MyBatis Generator adoption + Custom*Mapper — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sinh entity + mapper cho bảng `users` bằng mybatis-generator và dùng chúng làm gốc; query đặc thù (auth đa bảng) tách thành `CustomUserAuthMapper`; bổ sung rule generated-vs-custom vào CLAUDE.md.

**Architecture:** `mybatis-generator` sinh `entity.generator.User` + `dao.generator.UserMapper` (+ `UserCriteria`, XML) vào repo. Bỏ entity/mapper hand-written. Service dùng method generated (`insertSelective`, `selectByExample`/`selectOneByExample` với `UserCriteria` lọc `del_flag=0`). Auth đa bảng → `dao/custom/CustomUserAuthMapper` standalone. `@MapperScan("jp.co.htkk.persistence.dao")` quét cả `generator` + `custom`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, MyBatis 3.0.3, mybatis-generator-maven-plugin 1.4.2 (+ itfsw/oceanc/lombok plugins), PostgreSQL 16 / H2 test.

**Spec:** `docs/superpowers/specs/2026-06-13-mybatis-generator-custom-mapper-design.md`. Branch: `feature/generator-custom-mapper`.

**Build/verify:** Java 21 (`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`). Full build loại trừ tooling: `mvn ... -pl -mybatis-generator,-mybatis-schema-migration`. Shell in một khối env-var lớn trước output thật — bỏ qua. Trên Bash chạy docker/git/mvn dùng `dangerouslyDisableSandbox: true`.

---

## Key facts (verified)

- Generator config table: `<table tableName="users">` không có `domainObjectName` → sinh tên số nhiều `Users*`. Phải thêm `domainObjectName="User"` + `mapperName="UserMapper"`.
- `targetProject="../entity/src/main/java"` / `"../persistence/src/main/java"` là **tương đối với thư mục module** → phải chạy generator **từ trong `mybatis-generator/`** (`cd mybatis-generator && mvn mybatis-generator:generate`), KHÔNG chạy `mvn -pl mybatis-generator` từ root (recon cho thấy file ghi sai chỗ).
- Generator cần **DB sống** có bảng `users` (+ RBAC) → dùng dev Postgres: `JWT_SECRET=x docker compose up postgresDb -d` (init script tạo bảng). JDBC trong config: `localhost:5432/helpo_step`, `postgres/123456` — khớp dev compose.
- **Trạng thái trung gian:** sau khi sinh `dao.generator.UserMapper` mà `dao.UserMapper` hand-written còn → 2 bean cùng tên `userMapper` → context KHÔNG chạy. Vì vậy Task 2 chỉ compile + commit code sinh; app chỉ chạy lại sau Task 3 (đã xoá hand-written). 
- 5 file import `jp.co.htkk.entity.User`: `UserService`, `UserServiceImpl`, `UserController`, `UserResponse`, `UserListResponse`. `UserResponse` chỉ map `userId/username/email` (không lộ `password`).
- `SecurityUserServiceImpl` dùng `UserAuthMapper` + `UserAuthMapper.UserAuthRow`.

---

### Task 1: Sửa generatorConfig (tên gọn)

**Files:** Modify `mybatis-generator/src/main/resources/generatorConfig.xml`

- [ ] **Step 1: Thêm `domainObjectName` + `mapperName` vào `<table>`**

Đổi:
```xml
        <table tableName="users">
            <generatedKey column="user_id" sqlStatement="JDBC" identity="true"/>
        </table>
```
thành:
```xml
        <table tableName="users" domainObjectName="User" mapperName="UserMapper">
            <generatedKey column="user_id" sqlStatement="JDBC" identity="true"/>
        </table>
```

- [ ] **Step 2: Xác nhận XML hợp lệ**

Run: `xmllint --noout mybatis-generator/src/main/resources/generatorConfig.xml && echo OK` (nếu không có xmllint thì bỏ qua — chỉ kiểm tra mắt thường rằng chỉ đổi đúng dòng `<table>`).
Expected: `OK` (hoặc không lỗi).

- [ ] **Step 3: Commit**

```bash
git add mybatis-generator/src/main/resources/generatorConfig.xml
git commit -m "build(generator): name User table output User/UserMapper (domainObjectName/mapperName)"
```

---

### Task 2: Sinh code generated cho `users`

**Files:**
- Create (sinh): `entity/src/main/java/jp/co/htkk/entity/generator/User.java`, `UserCriteria.java`
- Create (sinh): `persistence/src/main/java/jp/co/htkk/persistence/dao/generator/UserMapper.java`, `UserMapper.xml`

- [ ] **Step 1: Bật dev Postgres (có bảng `users`)**

```bash
JWT_SECRET=x docker compose up postgresDb -d
until docker exec postgresDb pg_isready -U postgres -d helpo_step >/dev/null 2>&1; do sleep 1; done
docker exec postgresDb psql -U postgres -d helpo_step -c "\dt" | grep users && echo "DB READY"
```
Expected: `DB READY`.

- [ ] **Step 2: Chạy generator TỪ TRONG thư mục module**

```bash
cd mybatis-generator
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn mybatis-generator:generate
cd ..
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Xác nhận file sinh vào ĐÚNG chỗ trong repo**

```bash
find entity/src/main/java/jp/co/htkk/entity/generator persistence/src/main/java/jp/co/htkk/persistence/dao/generator -type f 2>/dev/null | sort
```
Expected: thấy `entity/generator/User.java`, `entity/generator/UserCriteria.java`, `dao/generator/UserMapper.java`, `dao/generator/UserMapper.xml`.
**Nếu rỗng** (path `../` resolve sai): kiểm tra file có rơi ra ngoài repo (`find /Users/dangnh/Documents/project/base -path '*jp/co/htkk*generator/User.java' -newermt '-5 minutes'`); nếu có, sửa `targetProject` trong generatorConfig thành đường dẫn đúng (hoặc chạy lại từ trong module) rồi lặp lại Step 2–3. KHÔNG để file sinh nằm ngoài repo.

- [ ] **Step 4: Kiểm tra entity Lombok có accessor CHUẨN (rủi ro chính)**

```bash
grep -E "class User|@Data|@Getter|@Setter|@Accessors|private .* password" entity/src/main/java/jp/co/htkk/entity/generator/User.java
```
Expected: entity có `password` + Lombok sinh `getX()/setX()` chuẩn (`@Data`/`@Getter`+`@Setter`; `@Accessors` không có `fluent=true`).
**Nếu** `@Accessors(fluent = true)` (getter/setter dạng `username()` thay vì `getUsername()`): mở `generatorConfig.xml`, trong plugin `com.softwareloop.mybatis.generator.plugins.LombokPlugin` đặt `<property name="accessors" value="false"/>`, rồi lặp lại Step 2–3 và kiểm tra lại.

- [ ] **Step 5: Ghi lại API generated thực tế (để Task 3 dùng đúng)**

```bash
grep -E "List<User> selectByExample|User selectOneByExample|int insertSelective|int insert\(" persistence/src/main/java/jp/co/htkk/persistence/dao/generator/UserMapper.java
grep -E "andUserIdEqualTo|andDelFlagEqualTo|createCriteria|setOrderByClause" entity/src/main/java/jp/co/htkk/entity/generator/UserCriteria.java | head
```
Expected: có `insertSelective(User)`, `selectByExample(UserCriteria)`; itfsw plugin thường có `selectOneByExample(UserCriteria)`; Criteria có `createCriteria()` trả về builder với `andUserIdEqualTo(Long)` + `andDelFlagEqualTo(Short)`, và `setOrderByClause(String)`.
Ghi lại tên/method THỰC TẾ — Task 3 phải khớp (vd nếu KHÔNG có `selectOneByExample` thì dùng `selectByExample` rồi lấy phần tử đầu).

- [ ] **Step 6: Compile-only (chưa chạy app — sẽ trùng bean với hand-written `UserMapper`)**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -DskipTests -pl persistence -am install 2>&1 | tail -3
```
Expected: `BUILD SUCCESS` (code sinh compile được). KHÔNG chạy test web/api ở task này (sẽ fail do trùng bean `userMapper` đến khi Task 3 xoá hand-written).

- [ ] **Step 7: Commit code generated**

```bash
git add entity/src/main/java/jp/co/htkk/entity/generator persistence/src/main/java/jp/co/htkk/persistence/dao/generator
git commit -m "feat(generator): generate User entity + UserMapper (+UserCriteria) for users table"
```

---

### Task 3: Chuyển sang dùng generated + tách CustomUserAuthMapper (swap)

Một task (làm trọn gói rồi build/test) vì các phần phụ thuộc nhau (app chỉ chạy lại khi xoá xong hand-written).

**Files:**
- Create: `persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserAuthMapper.java` + `CustomUserAuthMapper.xml`
- Delete: `persistence/.../dao/UserAuthMapper.java` + `.xml`; `persistence/.../dao/UserMapper.java` + `.xml`; `entity/.../entity/User.java`
- Modify: `UserServiceImpl.java`, `UserService.java`, `UserController.java`, `UserResponse.java`, `UserListResponse.java`, `SecurityUserServiceImpl.java`

- [ ] **Step 1: Tạo `CustomUserAuthMapper.java` (đổi tên + đổi package từ UserAuthMapper)**

`persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserAuthMapper.java`:
```java
package jp.co.htkk.persistence.dao.custom;

import java.util.List;

public interface CustomUserAuthMapper {
    /** Row from users by username (active only). */
    UserAuthRow findByUsername(String username);

    List<String> findRoleCodes(Long userId);

    List<String> findPermissionCodes(Long userId);

    class UserAuthRow {
        public Long userId;
        public String username;
        public String password;
        public Short delFlag;
    }
}
```

- [ ] **Step 2: Tạo `CustomUserAuthMapper.xml` (đổi namespace + type)**

`persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserAuthMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="jp.co.htkk.persistence.dao.custom.CustomUserAuthMapper">

    <resultMap id="userAuthRow" type="jp.co.htkk.persistence.dao.custom.CustomUserAuthMapper$UserAuthRow">
        <id column="user_id" jdbcType="BIGINT" property="userId"/>
        <result column="username" jdbcType="VARCHAR" property="username"/>
        <result column="password" jdbcType="VARCHAR" property="password"/>
        <result column="del_flag" jdbcType="SMALLINT" property="delFlag"/>
    </resultMap>

    <select id="findByUsername" resultMap="userAuthRow" parameterType="java.lang.String">
        SELECT user_id, username, password, del_flag
        FROM users
        WHERE username = #{username} AND del_flag = 0
    </select>

    <select id="findRoleCodes" resultType="java.lang.String" parameterType="java.lang.Long">
        SELECT r.role_code
        FROM user_roles ur
        JOIN roles r ON r.role_id = ur.role_id AND r.del_flag = 0
        WHERE ur.user_id = #{userId}
    </select>

    <select id="findPermissionCodes" resultType="java.lang.String" parameterType="java.lang.Long">
        SELECT DISTINCT p.permission_code
        FROM user_roles ur
        JOIN role_permissions rp ON rp.role_id = ur.role_id
        JOIN permissions p ON p.permission_id = rp.permission_id AND p.del_flag = 0
        WHERE ur.user_id = #{userId}
    </select>
</mapper>
```

- [ ] **Step 3: Xoá file hand-written cũ (UserAuthMapper, UserMapper, entity.User)**

```bash
git rm persistence/src/main/java/jp/co/htkk/persistence/dao/UserAuthMapper.java \
       persistence/src/main/java/jp/co/htkk/persistence/dao/UserAuthMapper.xml \
       persistence/src/main/java/jp/co/htkk/persistence/dao/UserMapper.java \
       persistence/src/main/java/jp/co/htkk/persistence/dao/UserMapper.xml \
       entity/src/main/java/jp/co/htkk/entity/User.java
```

- [ ] **Step 4: `SecurityUserServiceImpl` → CustomUserAuthMapper**

Trong `web/api/src/main/java/jp/co/htkk/api/security/SecurityUserServiceImpl.java`, đổi:
- import `jp.co.htkk.persistence.dao.UserAuthMapper;` → `jp.co.htkk.persistence.dao.custom.CustomUserAuthMapper;`
- field type `UserAuthMapper userAuthMapper` → `CustomUserAuthMapper userAuthMapper`
- mọi `UserAuthMapper.UserAuthRow` → `CustomUserAuthMapper.UserAuthRow`

Kết quả (toàn file):
```java
package jp.co.htkk.api.security;

import jp.co.htkk.persistence.dao.custom.CustomUserAuthMapper;
import jp.co.htkk.security.port.SecurityUser;
import jp.co.htkk.security.port.SecurityUserService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
@AllArgsConstructor
public class SecurityUserServiceImpl implements SecurityUserService {

    private final CustomUserAuthMapper userAuthMapper;

    @Override
    public SecurityUser loadByUsername(String username) {
        CustomUserAuthMapper.UserAuthRow row = userAuthMapper.findByUsername(username);
        if (row == null) {
            return null;
        }
        Set<String> roles = new LinkedHashSet<>(userAuthMapper.findRoleCodes(row.userId));
        Set<String> perms = new LinkedHashSet<>(userAuthMapper.findPermissionCodes(row.userId));
        return SecurityUser.builder()
                .uid(row.userId)
                .username(row.username)
                .passwordHash(row.password)
                .enabled(true)
                .roles(roles)
                .permissions(perms)
                .build();
    }
}
```

- [ ] **Step 5: Đổi import `entity.User` → `entity.generator.User` ở 4 file DTO/controller/interface**

Trong mỗi file sau đổi đúng dòng import `import jp.co.htkk.entity.User;` → `import jp.co.htkk.entity.generator.User;` (phần còn lại giữ nguyên):
- `dto/src/main/java/jp/co/htkk/dto/admin/user/response/UserResponse.java`
- `dto/src/main/java/jp/co/htkk/dto/admin/user/response/UserListResponse.java`
- `web/api/src/main/java/jp/co/htkk/api/controller/admin/UserController.java`
- `business/business-interface/src/main/java/jp/co/htkk/business/service/admin/UserService.java`

(`UserResponse.UserData.of` chỉ gọi `getUserId()/getUsername()/getEmail()` — có trên entity generated; không map `password`.)

- [ ] **Step 6: `UserServiceImpl` dùng mapper generated + UserCriteria**

Ghi đè `business/business-implementation/src/main/java/jp/co/htkk/business/service/impl/admin/UserServiceImpl.java`:
```java
package jp.co.htkk.business.service.impl.admin;

import jp.co.htkk.business.service.AbstractBaseService;
import jp.co.htkk.business.service.admin.UserService;
import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.entity.generator.User;
import jp.co.htkk.entity.generator.UserCriteria;
import jp.co.htkk.framework.exception.type.ServiceException;
import jp.co.htkk.persistence.dao.generator.UserMapper;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class UserServiceImpl extends AbstractBaseService implements UserService {

    private final UserMapper userMapper;   // generated

    @Override
    public User createUser(UserDxo dxo) {
        User user = new User();
        user.setUsername(dxo.getUsername());
        user.setEmail(dxo.getEmail());
        // AuditInterceptor điền created_by/at, updated_by/at; useGeneratedKeys set userId
        userMapper.insertSelective(user);
        return user;
    }

    @Override
    public User getUser(Long userId) {
        UserCriteria criteria = new UserCriteria();
        criteria.createCriteria().andUserIdEqualTo(userId).andDelFlagEqualTo((short) 0);
        User user = userMapper.selectOneByExample(criteria);
        if (user == null) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "User not found: " + userId);
        }
        return user;
    }

    @Override
    public List<User> listUsers() {
        UserCriteria criteria = new UserCriteria();
        criteria.createCriteria().andDelFlagEqualTo((short) 0);
        criteria.setOrderByClause("user_id");
        return userMapper.selectByExample(criteria);
    }
}
```
**Adapt:** dùng đúng tên class/method THỰC TẾ đã ghi ở Task 2 Step 5. Nếu `selectOneByExample` không tồn tại → thay `getUser` bằng:
```java
        List<User> list = userMapper.selectByExample(criteria);
        if (list.isEmpty()) { throw new ServiceException(HttpStatus.NOT_FOUND, "User not found: " + userId); }
        return list.get(0);
```
Nếu `UserCriteria` không nằm ở package `entity.generator` (vài cấu hình để Criteria ở `dao.generator`) → sửa import cho khớp Task 2 Step 5.

- [ ] **Step 7: Full build + integration test**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn clean install -pl -mybatis-generator,-mybatis-schema-migration 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD SUCCESS|BUILD FAILURE" | grep -v "Tests run: 0," | tail -8
```
Expected: `BUILD SUCCESS`; web/api integration tests xanh (UserCrud, UserValidation, SecurityIntegration, ApiContextLoads). Tests gọi HTTP nên không cần sửa; CRUD chạy qua generated mapper + Criteria; login/RBAC qua CustomUserAuthMapper.
Nếu lỗi compile/method-not-found → khớp lại với API generated (Task 2 Step 5) ở Step 6.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: use generated User entity + UserMapper (Criteria for del_flag); UserAuthMapper -> custom/CustomUserAuthMapper"
```

---

### Task 4: Rule generated-vs-custom vào CLAUDE.md

**Files:** Modify `CLAUDE.md`

- [ ] **Step 1: Thêm rule vào mục MyBatis trong CLAUDE.md**

Trong `CLAUDE.md`, ngay sau gạch đầu dòng cuối của mục "### MyBatis (quan trọng nhất — dễ sai)", thêm bullet:
```markdown
- **Generated vs custom mapper:** Entity + mapper CRUD 1 bảng do `mybatis-generator` sinh ở `*.generator.*` (KHÔNG sửa tay, bị ghi đè khi generate). **Ưu tiên method generated**: lọc/sort/đếm 1 bảng dùng `selectByExample`/`selectOneByExample` + `*Criteria` (vd `del_flag=0`) — không cần custom. Chỉ tạo `Custom<Tên>Mapper` ở `persistence/dao/custom/` khi generated **không làm được** (join đa bảng, query đặc thù): đa bảng → standalone; 1 bảng đặc thù → `extends` mapper generated (MyBatis resolve method kế thừa về namespace cha). Sinh code: `cd mybatis-generator && mvn mybatis-generator:generate` (cần DB sống; chạy TỪ TRONG thư mục module để path `../` đúng; bảng `users` đã set `domainObjectName=User`).
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(CLAUDE): rule generated-vs-custom mapper convention"
```

---

### Task 5: Live verification + dọn dẹp

- [ ] **Step 1: Chạy stack dev và smoke User CRUD + auth (qua generated mapper)**

```bash
JWT_SECRET=dev-secret-please-change-0123456789-abcdefghij docker compose up --build -d
BASE=http://localhost:9000/api/v1
curl -s --retry 40 --retry-delay 2 --retry-connrefused -o /dev/null -w 'health %{http_code}\n' "$BASE/actuator/health"
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
echo "create: $(curl -s -X POST "$BASE/admin/users" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"username":"genuser","email":"gen@example.com"}')"
echo "list  : $(curl -s "$BASE/admin/users" -H "Authorization: Bearer $TOKEN")"
```
Expected: health `200`; login trả token; create trả `{"data":{"userId":...}}`; list chứa `genuser` (qua `selectByExample` lọc del_flag).

- [ ] **Step 2: Tear down**

```bash
docker compose down -v
```

- [ ] **Step 3: Cây git sạch + build cuối**

```bash
git status --short
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q clean install -pl -mybatis-generator,-mybatis-schema-migration > /tmp/final.log 2>&1; echo "exit=$?"
```
Expected: working tree sạch; `exit=0`.

---

## Verification Summary (maps to spec §Verification)

1. Generator sinh đúng `entity.generator.User` + `dao.generator.UserMapper` vào repo; entity accessor chuẩn. ✔ Task 2.3–2.4
2. Full build xanh, mọi import `entity.generator.User` compile; mapper generated + custom load. ✔ Task 3.7
3. Integration test web/api xanh (CRUD qua generated + Criteria; auth qua CustomUserAuthMapper). ✔ Task 3.7
4. Live dev: create/get/list + login OK; created_by đúng. ✔ Task 5.1
5. `git status` sạch (không sót file generated ngoài repo). ✔ Task 5.3

## Out of scope
- Sinh mapper cho bảng RBAC khác (roles/permissions…).
- CustomUserMapper (del_flag đã dùng Criteria).

# MyBatis Generator adoption + Custom*Mapper convention — Design Spec

**Ngày:** 2026-06-13
**Nhánh:** `feature/generator-custom-mapper`

## Bối cảnh & vấn đề

Dự án dùng `mybatis-generator` để sinh entity + mapper. Trong các sub-project trước, các mapper được **viết tay** đặt sai chỗ:
- `entity/User.java` (hand-written, **thiếu cột `password`**).
- `persistence/dao/UserMapper.java` + `.xml` (hand-written CRUD 1 bảng).
- `persistence/dao/UserAuthMapper.java` + `.xml` (hand-written, query auth **đa bảng** — join users/roles/permissions/user_roles/role_permissions).

Chủ dự án lo các file này xung đột/bị thay thế khi chạy generator. Quy ước gốc của base: code sinh ở `*.generator.*` (ghi đè mỗi lần generate), code tay ở `dao/custom/Custom*Mapper`.

### Phát hiện qua recon (chạy generator thử rồi revert)

1. **Tên generated theo tên bảng (số nhiều):** `<table tableName="users">` không set `domainObjectName` → sinh `Users`/`UsersMapper`/`UsersCriteria`. Phải set `domainObjectName="User"` + `mapperName="UserMapper"` để có tên gọn `User`/`UserMapper`/`UserCriteria`.
2. **`targetProject="../..."` mong manh:** chạy `mvn -pl mybatis-generator` từ root → path `../` resolve sai, file không vào repo. Phải chạy **từ trong thư mục `mybatis-generator/`** thì `../entity` mới trỏ đúng `springboot-multi-module/entity`.
3. **Generated entity đầy đủ hơn** (có `password`), nên dùng làm gốc đúng hơn entity hand-written.
4. **Rủi ro Lombok:** plugin `mybatis-generator-lombok-plugin` cấu hình `accessors=true` → cần verify entity sinh ra vẫn có `getX()/setX()` chuẩn (không fluent/chain phá vỡ DTO/audit). Nếu lệch → chỉnh property Lombok trong `generatorConfig`.

## Quyết định (đã chốt qua brainstorming)

- **Dùng generated làm gốc** cho bảng `users`: bỏ `entity.User` + `dao.UserMapper` hand-written, dùng `entity.generator.User` + `dao.generator.UserMapper`.
- Thêm `domainObjectName="User"` + `mapperName="UserMapper"` vào `<table>`.
- **Quy ước Custom*Mapper** ở `dao/custom/`: 1 bảng → `extends` mapper generated; đa bảng → standalone.
- Thêm **rule vào `CLAUDE.md`**.

## Phạm vi

**Trong scope:**
1. Sửa `generatorConfig.xml` (`domainObjectName`/`mapperName` cho bảng `users`).
2. Sinh lại code cho `users` → `entity.generator.User`, `dao.generator.UserMapper` (+ `UserCriteria`, XML) vào repo.
3. Bỏ `entity/User.java` + `dao/UserMapper.java`/`.xml` hand-written.
4. Tạo `dao/custom/CustomUserMapper` (extends `dao.generator.UserMapper`) cho read lọc `del_flag=0`.
5. Đổi `dao/UserAuthMapper` → `dao/custom/CustomUserAuthMapper` (standalone, đa bảng).
6. Cập nhật consumers: 5 file import `entity.User` → `entity.generator.User`; `UserServiceImpl` dùng generated insert + CustomUserMapper read; `SecurityUserServiceImpl` dùng `CustomUserAuthMapper`.
7. Thêm rule vào `CLAUDE.md`.

**Ngoài scope:**
- Sinh mapper cho các bảng RBAC khác (roles/permissions…) — chúng chỉ đọc qua join trong `CustomUserAuthMapper`, không cần per-table CRUD.
- Sửa lại toàn bộ DTO flow / service khác.

## File structure

| File | Hành động |
|---|---|
| `mybatis-generator/src/main/resources/generatorConfig.xml` | Sửa: thêm `domainObjectName="User" mapperName="UserMapper"` |
| `entity/.../entity/generator/User.java` | **Sinh** (generator) |
| `entity/.../entity/generator/UserCriteria.java` | **Sinh** (RenameExampleClassPlugin) |
| `persistence/.../dao/generator/UserMapper.java` + `UserMapper.xml` | **Sinh** (generator) |
| `entity/.../entity/User.java` | **Xoá** (hand-written) |
| `persistence/.../dao/UserMapper.java` + `UserMapper.xml` | **Xoá** (hand-written) |
| `persistence/.../dao/UserAuthMapper.java` + `.xml` | **Đổi tên/đổi chỗ** → `dao/custom/CustomUserAuthMapper.java` + `.xml` |
| `persistence/.../dao/custom/CustomUserMapper.java` + `.xml` | **Tạo** (extends generated, read lọc del_flag) |
| `business-interface/.../admin/UserService.java` | Sửa import → `entity.generator.User` |
| `business-implementation/.../admin/UserServiceImpl.java` | Sửa: entity generated + insert generated + CustomUserMapper read |
| `web/api/.../controller/admin/UserController.java` | Sửa import → `entity.generator.User` |
| `web/api/.../security/SecurityUserServiceImpl.java` | Sửa: `UserAuthMapper` → `CustomUserAuthMapper` |
| `dto/.../user/response/UserResponse.java`, `UserListResponse.java` | Sửa import → `entity.generator.User` |
| `CLAUDE.md` | Thêm rule generated-vs-custom mapper |

## Thiết kế chi tiết

### 1. generatorConfig.xml
```xml
<table tableName="users" domainObjectName="User" mapperName="UserMapper">
    <generatedKey column="user_id" sqlStatement="JDBC" identity="true"/>
</table>
```
Lệnh sinh (cần DB sống có bảng `users`; chạy **từ thư mục module** để `../` resolve đúng):
```bash
JWT_SECRET=x docker compose up postgresDb -d        # DB dev có bảng users + RBAC
cd mybatis-generator
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn mybatis-generator:generate
cd ..
```
Verify file sinh ở `entity/src/main/java/jp/co/htkk/entity/generator/User.java` và `persistence/src/main/java/jp/co/htkk/persistence/dao/generator/UserMapper.java` (+ `.xml`). **Kiểm tra entity có `getUserId()/setUsername()…` chuẩn** (nếu Lombok sinh accessor fluent → set `accessors=false` trong plugin Lombok rồi sinh lại).

### 2. Generated UserMapper (MyBatis3) — method chính dùng
- `int insert(User)` / `int insertSelective(User)` — dùng cho create (useGeneratedKeys set userId).
- `User selectByPrimaryKey(Long)` — **không** lọc del_flag.
- `List<User> selectByExample(UserCriteria)` — đa năng (lọc, sort).
- `updateByPrimaryKeySelective`, `deleteByPrimaryKey`, … (chưa dùng nhưng có sẵn).

### 3. CustomUserMapper (single-table → extends generated)
`persistence/src/main/java/jp/co/htkk/persistence/dao/custom/CustomUserMapper.java`:
```java
package jp.co.htkk.persistence.dao.custom;

import jp.co.htkk.entity.generator.User;
import jp.co.htkk.persistence.dao.generator.UserMapper;
import java.util.List;

/** Single-table; kế thừa method generated + thêm read lọc soft-delete (del_flag=0). */
public interface CustomUserMapper extends UserMapper {
    User selectActiveById(Long userId);
    List<User> selectAllActive();
}
```
`CustomUserMapper.xml` (colocated, namespace = CustomUserMapper, resultMap dùng lại từ generated qua include hoặc khai báo cột tường minh):
```xml
<select id="selectActiveById" resultType="jp.co.htkk.entity.generator.User" parameterType="java.lang.Long">
    SELECT user_id, username, email, password, created_by, created_at, updated_by, updated_at, del_flag
    FROM users WHERE user_id = #{userId} AND del_flag = 0
</select>
<select id="selectAllActive" resultType="jp.co.htkk.entity.generator.User">
    SELECT user_id, username, email, password, created_by, created_at, updated_by, updated_at, del_flag
    FROM users WHERE del_flag = 0 ORDER BY user_id
</select>
```
(Vì `MyBatisConfig` đặt `mapUnderscoreToCamelCase=true`, `resultType` map cột snake_case → field camelCase tự động cho 2 query này.)

### 4. CustomUserAuthMapper (đa bảng → standalone)
Đổi tên `UserAuthMapper` → `dao/custom/CustomUserAuthMapper.java` (+ `.xml`), namespace cập nhật. Nội dung query giữ nguyên (findByUsername active, findRoleCodes, findPermissionCodes). `UserAuthRow` inner class giữ nguyên.

### 5. Service refactor
`UserServiceImpl` dùng `CustomUserMapper` (vì nó extends `UserMapper` nên có cả method generated + custom):
```java
private final CustomUserMapper userMapper;   // extends generated UserMapper

public User createUser(UserDxo dxo) {
    User user = new User();
    user.setUsername(dxo.getUsername());
    user.setEmail(dxo.getEmail());
    userMapper.insertSelective(user);   // generated; audit + useGeneratedKeys
    return user;
}
public User getUser(Long userId) {
    User user = userMapper.selectActiveById(userId);   // custom (del_flag=0)
    if (user == null) throw new ServiceException(HttpStatus.NOT_FOUND, "User not found: " + userId);
    return user;
}
public List<User> listUsers() { return userMapper.selectAllActive(); }   // custom
```
`@MapperScan("jp.co.htkk.persistence.dao")` quét cả `dao.generator` + `dao.custom`; bean `customUserMapper`/`customUserAuthMapper` không trùng tên với `userMapper` generated.

## Verification

1. `cd mybatis-generator && mvn mybatis-generator:generate` (DB dev đang chạy) sinh đúng `entity.generator.User` + `dao.generator.UserMapper` vào repo; entity có accessor chuẩn.
2. `mvn clean install -pl -mybatis-generator,-mybatis-schema-migration` xanh (mọi import `entity.generator.User` compile; mapper custom + generated load).
3. Integration test web/api xanh: User CRUD (create→insertSelective, get→selectActiveById, list→selectAllActive), login/RBAC (CustomUserAuthMapper) — y như trước teardown.
4. Live dev: `docker compose up --build` → tạo user, get, list; login admin → token; `created_by` đúng.
5. `git status` sạch (không sót file generated ngoài repo).

## Rủi ro & xử lý
- **Lombok accessor lệch chuẩn** → set `accessors=false` (hoặc tinh chỉnh) trong plugin Lombok của `generatorConfig`, sinh lại.
- **del_flag** không được method generated lọc → đã giải quyết bằng `CustomUserMapper`.
- **`password` mới xuất hiện trên entity** → `UserResponse` hiện chỉ map userId/username/email nên không lộ password; xác nhận `UserResponse.of(User)` không vô tình trả password.

## CLAUDE.md rule (sẽ thêm)
> **MyBatis generated vs custom mapper:** Entity + mapper CRUD 1 bảng do `mybatis-generator` sinh ở `*.generator.*` (KHÔNG sửa tay, bị ghi đè khi generate). Khi cần query mà method generated **không đủ** (join đa bảng, lọc `del_flag`, query đặc thù) → tạo `Custom<Tên>Mapper` trong `persistence/dao/custom/`: **1 bảng thì `extends` mapper generated**; **đa bảng thì standalone**. Ưu tiên dùng method generated sẵn có; chỉ thêm custom cho phần thiếu. Sinh code: `cd mybatis-generator && mvn mybatis-generator:generate` (cần DB sống; chạy từ trong thư mục module để path `../` đúng).

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
- **del_flag lọc bằng `UserCriteria`** (`selectByExample`/`selectOneByExample` — method generated) → **không tạo `CustomUserMapper`**.
- **Quy ước Custom*Mapper** ở `dao/custom/` chỉ khi generated không đủ: đa bảng → standalone; (1 bảng nếu cần query đặc thù → `extends` generated).
- Thêm **rule vào `CLAUDE.md`**.

## Phạm vi

**Trong scope:**
1. Sửa `generatorConfig.xml` (`domainObjectName`/`mapperName` cho bảng `users`).
2. Sinh lại code cho `users` → `entity.generator.User`, `dao.generator.UserMapper` (+ `UserCriteria`, XML) vào repo.
3. Bỏ `entity/User.java` + `dao/UserMapper.java`/`.xml` hand-written.
4. Đổi `dao/UserAuthMapper` → `dao/custom/CustomUserAuthMapper` (standalone, đa bảng).
5. Cập nhật consumers: 5 file import `entity.User` → `entity.generator.User`; `UserServiceImpl` dùng generated `insertSelective` + `selectByExample`/`selectOneByExample` (UserCriteria lọc del_flag); `SecurityUserServiceImpl` dùng `CustomUserAuthMapper`.
6. Thêm rule vào `CLAUDE.md`.

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
| `business-interface/.../admin/UserService.java` | Sửa import → `entity.generator.User` |
| `business-implementation/.../admin/UserServiceImpl.java` | Sửa: entity generated + `insertSelective` + read qua `UserCriteria` |
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

### 3. del_flag — dùng UserCriteria (KHÔNG tạo CustomUserMapper)
Method generated đủ để lọc soft-delete trên 1 bảng:
- `getUser(id)` → `selectOneByExample` (itfsw SelectOneByExamplePlugin) với criteria `userId = X AND delFlag = 0`.
- `listUsers()` → `selectByExample` với criteria `delFlag = 0`, `orderByClause = "user_id"`.

```java
// get active by id
UserCriteria c = new UserCriteria();
c.createCriteria().andUserIdEqualTo(userId).andDelFlagEqualTo((short) 0);
User user = userMapper.selectOneByExample(c);   // null nếu không có

// list active
UserCriteria c = new UserCriteria();
c.createCriteria().andDelFlagEqualTo((short) 0);
c.setOrderByClause("user_id");
List<User> users = userMapper.selectByExample(c);
```
(Nếu `selectOneByExample` không sinh ra như kỳ vọng → fallback `selectByExample` rồi lấy phần tử đầu.)

### 4. CustomUserAuthMapper (đa bảng → standalone)
Đổi tên `UserAuthMapper` → `dao/custom/CustomUserAuthMapper.java` (+ `.xml`), namespace cập nhật. Nội dung query giữ nguyên (findByUsername active, findRoleCodes, findPermissionCodes). `UserAuthRow` inner class giữ nguyên.

### 5. Service refactor
`UserServiceImpl` dùng **mapper generated** trực tiếp + `UserCriteria` (không có CustomUserMapper):
```java
import jp.co.htkk.entity.generator.User;
import jp.co.htkk.persistence.dao.generator.UserMapper;
import jp.co.htkk.persistence.dao.generator.UserCriteria;

private final UserMapper userMapper;   // generated

public User createUser(UserDxo dxo) {
    User user = new User();
    user.setUsername(dxo.getUsername());
    user.setEmail(dxo.getEmail());
    userMapper.insertSelective(user);   // generated; audit + useGeneratedKeys set userId
    return user;
}
public User getUser(Long userId) {
    UserCriteria c = new UserCriteria();
    c.createCriteria().andUserIdEqualTo(userId).andDelFlagEqualTo((short) 0);
    User user = userMapper.selectOneByExample(c);
    if (user == null) throw new ServiceException(HttpStatus.NOT_FOUND, "User not found: " + userId);
    return user;
}
public List<User> listUsers() {
    UserCriteria c = new UserCriteria();
    c.createCriteria().andDelFlagEqualTo((short) 0);
    c.setOrderByClause("user_id");
    return userMapper.selectByExample(c);
}
```
`@MapperScan("jp.co.htkk.persistence.dao")` quét cả `dao.generator` (bean `userMapper`) + `dao.custom` (bean `customUserAuthMapper`) — không trùng tên.

## Verification

1. `cd mybatis-generator && mvn mybatis-generator:generate` (DB dev đang chạy) sinh đúng `entity.generator.User` + `dao.generator.UserMapper` vào repo; entity có accessor chuẩn.
2. `mvn clean install -pl -mybatis-generator,-mybatis-schema-migration` xanh (mọi import `entity.generator.User` compile; mapper custom + generated load).
3. Integration test web/api xanh: User CRUD (create→`insertSelective`, get→`selectOneByExample`, list→`selectByExample` với Criteria del_flag=0), login/RBAC (CustomUserAuthMapper) — y như trước teardown.
4. Live dev: `docker compose up --build` → tạo user, get, list; login admin → token; `created_by` đúng.
5. `git status` sạch (không sót file generated ngoài repo).

## Rủi ro & xử lý
- **Lombok accessor lệch chuẩn** → set `accessors=false` (hoặc tinh chỉnh) trong plugin Lombok của `generatorConfig`, sinh lại.
- **del_flag** lọc bằng `UserCriteria` (`selectByExample`/`selectOneByExample`) — method generated, không cần custom mapper. (Nếu `selectOneByExample` không có → `selectByExample` lấy phần tử đầu.)
- **`password` mới xuất hiện trên entity** → `UserResponse` hiện chỉ map userId/username/email nên không lộ password; xác nhận `UserResponse.of(User)` không vô tình trả password.

## CLAUDE.md rule (sẽ thêm)
> **MyBatis generated vs custom mapper:** Entity + mapper CRUD 1 bảng do `mybatis-generator` sinh ở `*.generator.*` (KHÔNG sửa tay, bị ghi đè khi generate). **Ưu tiên dùng method generated**: lọc/sort/đếm trên 1 bảng dùng `selectByExample`/`selectOneByExample` với `*Criteria` (vd lọc `del_flag=0`) — KHÔNG cần custom. Chỉ tạo `Custom<Tên>Mapper` trong `persistence/dao/custom/` khi generated **thực sự không làm được** (join đa bảng, query đặc thù): **đa bảng → standalone**; (1 bảng cần query đặc thù → `extends` mapper generated — MyBatis tự resolve method kế thừa về namespace cha). Sinh code: `cd mybatis-generator && mvn mybatis-generator:generate` (cần DB sống; chạy từ trong thư mục module để path `../` đúng).

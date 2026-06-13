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

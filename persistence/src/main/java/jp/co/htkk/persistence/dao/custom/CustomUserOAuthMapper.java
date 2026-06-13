package jp.co.htkk.persistence.dao.custom;

import org.apache.ibatis.annotations.Param;

public interface CustomUserOAuthMapper {

    /** Active user matching a Google sub, or null. */
    OAuthUserRow findByGoogleSub(@Param("googleSub") String googleSub);

    /** Active user matching an email, or null. */
    OAuthUserRow findByEmail(@Param("email") String email);

    /** Links a Google sub onto an existing user. Returns rows affected. */
    int linkGoogleSub(@Param("userId") Long userId, @Param("googleSub") String googleSub);

    /** role_id for an active role_code, or null. */
    Long findRoleIdByCode(@Param("roleCode") String roleCode);

    /** Inserts a user_roles row. Returns rows affected. */
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    class OAuthUserRow {
        public Long userId;
        public String username;
    }
}

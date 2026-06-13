package jp.co.htkk.api.security;

import jp.co.htkk.entity.generator.User;
import jp.co.htkk.framework.enums.EDeleteFlag;
import jp.co.htkk.persistence.dao.custom.CustomUserAuthMapper;
import jp.co.htkk.persistence.dao.custom.CustomUserOAuthMapper;
import jp.co.htkk.persistence.dao.custom.CustomUserOAuthMapper.OAuthUserRow;
import jp.co.htkk.persistence.dao.generator.UserMapper;
import jp.co.htkk.security.google.config.GoogleOAuthProperties;
import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.port.GoogleUserSyncService;
import jp.co.htkk.security.port.SecurityUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleUserSyncServiceImpl implements GoogleUserSyncService {

    private final CustomUserOAuthMapper oauthMapper;
    private final CustomUserAuthMapper authMapper;   // reused for roles/permissions
    private final UserMapper userMapper;             // generated, single-table insert
    private final GoogleOAuthProperties props;

    @Override
    @Transactional
    public SecurityUser syncFromGoogle(GoogleUserInfo info) {
        Long userId;
        String username;

        OAuthUserRow row = oauthMapper.findByGoogleSub(info.getSub());
        if (row != null) {
            userId = row.userId;
            username = row.username;
        } else {
            row = oauthMapper.findByEmail(info.getEmail());
            if (row != null) {
                oauthMapper.linkGoogleSub(row.userId, info.getSub());
                userId = row.userId;
                username = row.username;
            } else {
                User created = createOauthUser(info);
                userId = created.getUserId();
                username = created.getUsername();
            }
        }

        Set<String> roles = new LinkedHashSet<>(authMapper.findRoleCodes(userId));
        Set<String> perms = new LinkedHashSet<>(authMapper.findPermissionCodes(userId));
        return SecurityUser.builder()
                .uid(userId)
                .username(username)
                .passwordHash("")
                .enabled(true)
                .roles(roles)
                .permissions(perms)
                .build();
    }

    private User createOauthUser(GoogleUserInfo info) {
        try {
            User u = new User();
            u.setUsername(info.getEmail());
            u.setEmail(info.getEmail());
            u.setPassword("");
            u.setGoogleSub(info.getSub());
            u.setDelFlag(EDeleteFlag.NOT_DELETED.getCode());
            userMapper.insertSelective(u);   // useGeneratedKeys populates userId

            Long roleId = oauthMapper.findRoleIdByCode(props.getDefaultRoleCode());
            if (roleId == null) {
                throw new IllegalStateException("Default OAuth role not found: " + props.getDefaultRoleCode());
            }
            oauthMapper.insertUserRole(u.getUserId(), roleId);
            return u;
        } catch (DuplicateKeyException e) {
            // Concurrent request inserted the same google_sub first; re-fetch the winner.
            log.warn("Concurrent OAuth user insert for google_sub={}, re-fetching", info.getSub());
            OAuthUserRow existing = oauthMapper.findByGoogleSub(info.getSub());
            if (existing == null) {
                throw new BadCredentialsException("User sync failed");
            }
            User u = new User();
            u.setUserId(existing.userId);
            u.setUsername(existing.username);
            return u;
        }
    }
}

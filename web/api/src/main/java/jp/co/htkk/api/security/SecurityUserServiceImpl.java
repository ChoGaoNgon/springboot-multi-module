package jp.co.htkk.api.security;

import jp.co.htkk.persistence.dao.UserAuthMapper;
import jp.co.htkk.security.port.SecurityUser;
import jp.co.htkk.security.port.SecurityUserService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
@AllArgsConstructor
public class SecurityUserServiceImpl implements SecurityUserService {

    private final UserAuthMapper userAuthMapper;

    @Override
    public SecurityUser loadByUsername(String username) {
        UserAuthMapper.UserAuthRow row = userAuthMapper.findByUsername(username);
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

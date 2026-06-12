package jp.co.htkk.security.port;

import lombok.Builder;
import lombok.Value;

import java.util.Set;

@Value
@Builder
public class SecurityUser {
    Long uid;
    String username;
    String passwordHash;
    boolean enabled;
    Set<String> roles;
    Set<String> permissions;
}

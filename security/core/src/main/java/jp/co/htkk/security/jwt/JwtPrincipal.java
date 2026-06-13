package jp.co.htkk.security.jwt;

import lombok.Value;

import java.util.Set;

@Value
public class JwtPrincipal {
    Long uid;
    String username;
    Set<String> roles;
    Set<String> permissions;
}

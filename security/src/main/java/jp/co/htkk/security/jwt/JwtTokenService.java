package jp.co.htkk.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jp.co.htkk.security.config.SecurityModuleProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class JwtTokenService {

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMS = "perms";

    private final SecretKey key;
    private final SecurityModuleProperties props;

    public JwtTokenService(SecurityModuleProperties props) {
        this.props = props;
        String secret = props.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.security.jwt.secret must be set and at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(Long uid, String username, Set<String> roles, Set<String> permissions) {
        return issueWithTtl(uid, username, roles, permissions, props.getJwt().getExpiration());
    }

    public String issueWithTtl(Long uid, String username, Set<String> roles, Set<String> permissions, Duration ttl) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttl.toMillis());
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_UID, uid)
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_PERMS, permissions)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public JwtPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Long uid = claims.get(CLAIM_UID, Number.class).longValue();
            Set<String> roles = toStringSet(claims.get(CLAIM_ROLES, List.class));
            Set<String> perms = toStringSet(claims.get(CLAIM_PERMS, List.class));
            return new JwtPrincipal(uid, claims.getSubject(), roles, perms);
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            throw new InvalidTokenException("Invalid or expired JWT", ex);
        }
    }

    /** Returns a fresh token (same identity, new TTL) iff the current token is still valid and within the renew window. */
    public Optional<String> renewIfNeeded(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty(); // expired/invalid -> caller handles as 401, never renew
        }
        long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remaining > props.getJwt().getRenewWindow().toMillis()) {
            return Optional.empty();
        }
        JwtPrincipal p = parse(token);
        return Optional.of(issue(p.getUid(), p.getUsername(), p.getRoles(), p.getPermissions()));
    }

    @SuppressWarnings("unchecked")
    private Set<String> toStringSet(List<?> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (Object o : raw) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}

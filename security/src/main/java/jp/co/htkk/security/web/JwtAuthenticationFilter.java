package jp.co.htkk.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.co.htkk.framework.security.model.LoginInfo;
import jp.co.htkk.security.jwt.JwtPrincipal;
import jp.co.htkk.security.jwt.JwtTokenService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String RENEW_HEADER = "X-New-Access-Token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService tokenService;

    public JwtAuthenticationFilter(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                JwtPrincipal principal = tokenService.parse(token);

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                principal.getRoles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                principal.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal.getUsername(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                LoginInfo.set(new LoginInfo(principal.getUid(), principal.getUsername()));

                tokenService.renewIfNeeded(token).ifPresent(t -> response.setHeader(RENEW_HEADER, t));
            } catch (JwtTokenService.InvalidTokenException ignored) {
                // leave context empty -> entry point returns 401
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            LoginInfo.clear();
        }
    }
}

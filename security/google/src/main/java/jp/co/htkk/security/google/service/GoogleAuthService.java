package jp.co.htkk.security.google.service;

import jp.co.htkk.security.config.SecurityModuleProperties;
import jp.co.htkk.security.google.config.GoogleOAuthProperties;
import jp.co.htkk.security.google.port.GoogleUserInfo;
import jp.co.htkk.security.google.port.GoogleUserSyncService;
import jp.co.htkk.security.jwt.JwtTokenService;
import jp.co.htkk.security.port.SecurityUser;
import jp.co.htkk.security.web.dto.LoginResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;

@Slf4j
public class GoogleAuthService {

    private final GoogleTokenClient tokenClient;
    private final GoogleIdTokenVerifier idTokenVerifier;
    private final GoogleUserSyncService userSyncService;
    private final JwtTokenService tokenService;
    private final GoogleOAuthProperties googleProps;
    private final SecurityModuleProperties securityProps;

    public GoogleAuthService(GoogleTokenClient tokenClient, GoogleIdTokenVerifier idTokenVerifier,
                             GoogleUserSyncService userSyncService, JwtTokenService tokenService,
                             GoogleOAuthProperties googleProps, SecurityModuleProperties securityProps) {
        this.tokenClient = tokenClient;
        this.idTokenVerifier = idTokenVerifier;
        this.userSyncService = userSyncService;
        this.tokenService = tokenService;
        this.googleProps = googleProps;
        this.securityProps = securityProps;
    }

    public LoginResponse handleCallback(String code, String redirectUri) {
        if (!googleProps.getAllowedRedirectUris().contains(redirectUri)) {
            log.warn("Rejected Google callback with non-whitelisted redirectUri: {}", redirectUri);
            throw new BadCredentialsException("Invalid redirect URI");
        }

        GoogleTokenResponse tokens = tokenClient.exchange(code, redirectUri);
        GoogleUserInfo info = idTokenVerifier.verify(tokens.getIdToken());

        if (!info.isEmailVerified()) {
            throw new BadCredentialsException("Google email not verified");
        }
        if (info.getEmail() == null || info.getEmail().isBlank()) {
            throw new BadCredentialsException("Google email scope required");
        }

        SecurityUser user = userSyncService.syncFromGoogle(info);
        if (!user.isEnabled()) {
            throw new BadCredentialsException("Account disabled");
        }

        String token = tokenService.issue(user.getUid(), user.getUsername(), user.getRoles(), user.getPermissions());
        long expiresIn = securityProps.getJwt().getExpiration().toSeconds();
        return new LoginResponse(token, "Bearer", expiresIn);
    }
}

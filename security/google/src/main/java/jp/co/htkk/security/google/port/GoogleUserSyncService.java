package jp.co.htkk.security.google.port;

import jp.co.htkk.security.port.SecurityUser;

/** Implemented by the consuming application: find/link/create the local user for a verified Google identity. */
public interface GoogleUserSyncService {
    /**
     * Find the existing user by Google sub, else link by verified email, else create a new user.
     *
     * @return a {@link SecurityUser} ready for {@code JwtTokenService.issue(...)}
     * @throws org.springframework.security.authentication.BadCredentialsException if the account is unusable
     */
    SecurityUser syncFromGoogle(GoogleUserInfo info);
}

package jp.co.htkk.security.port;

/** Implemented by the consuming application to supply user credentials + authorities. */
public interface SecurityUserService {
    /** @return the user, or null if not found. */
    SecurityUser loadByUsername(String username);
}

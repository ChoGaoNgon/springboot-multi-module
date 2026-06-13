package jp.co.htkk.security.google.service;

/** Upstream/Google service failure (network, 5xx) — mapped to HTTP 502 by the app's advice. */
public class GoogleAuthException extends RuntimeException {
    public GoogleAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}

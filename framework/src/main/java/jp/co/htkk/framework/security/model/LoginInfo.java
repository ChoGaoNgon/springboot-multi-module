package jp.co.htkk.framework.security.model;

import lombok.Value;

/** Per-request authenticated principal, populated by the security module's JWT filter. */
@Value
public class LoginInfo {

    Long uid;
    String username;

    private static final ThreadLocal<LoginInfo> CONTEXT = new ThreadLocal<>();

    public static void set(LoginInfo loginInfo) {
        CONTEXT.set(loginInfo);
    }

    public static LoginInfo fromContext() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

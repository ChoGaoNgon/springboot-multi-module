package jp.co.htkk.security.google.port;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GoogleUserInfo {
    String sub;             // immutable Google account id; primary identifier
    String email;
    boolean emailVerified;
    String name;
    String picture;
}

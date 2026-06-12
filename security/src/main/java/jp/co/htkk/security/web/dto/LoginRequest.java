package jp.co.htkk.security.web.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
}

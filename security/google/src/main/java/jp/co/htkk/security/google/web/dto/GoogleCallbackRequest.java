package jp.co.htkk.security.google.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleCallbackRequest {
    @NotBlank
    private String code;
    @NotBlank
    private String redirectUri;
}

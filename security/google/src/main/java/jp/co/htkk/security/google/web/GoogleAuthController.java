package jp.co.htkk.security.google.web;

import jakarta.validation.Valid;
import jp.co.htkk.security.google.service.GoogleAuthService;
import jp.co.htkk.security.google.web.dto.GoogleCallbackRequest;
import jp.co.htkk.security.web.dto.LoginResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// @RestController so the app's component scan (scanBasePackages = "jp.co.htkk") registers it as a
// handler, mirroring AuthController in security-core. Not registered as a @Bean by the auto-config.
@RestController
@RequestMapping("/auth/google")
public class GoogleAuthController {

    private final GoogleAuthService service;

    public GoogleAuthController(GoogleAuthService service) {
        this.service = service;
    }

    @PostMapping("/callback")
    public ResponseEntity<LoginResponse> callback(@Valid @RequestBody GoogleCallbackRequest req) {
        return ResponseEntity.ok(service.handleCallback(req.getCode(), req.getRedirectUri()));
    }
}

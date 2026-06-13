package jp.co.htkk.security.web;

import jp.co.htkk.security.config.SecurityModuleProperties;
import jp.co.htkk.security.jwt.JwtTokenService;
import jp.co.htkk.security.port.SecurityUser;
import jp.co.htkk.security.port.SecurityUserService;
import jp.co.htkk.security.web.dto.LoginRequest;
import jp.co.htkk.security.web.dto.LoginResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// A @RestController so it is reliably picked up as a request handler by the app's component scan
// (scanBasePackages = "jp.co.htkk", which covers this module once the dependency is on the
// classpath). It is intentionally NOT also registered as a @Bean by the auto-config, so there is
// no double registration. A plain @Bean with only a type-level @RequestMapping was NOT detected as
// a handler in this Spring MVC setup, leaving /auth/login unmapped.
@RestController
public class AuthController {

    private final SecurityUserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final SecurityModuleProperties props;

    public AuthController(SecurityUserService userService, PasswordEncoder passwordEncoder,
                          JwtTokenService tokenService, SecurityModuleProperties props) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.props = props;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        SecurityUser user = userService.loadByUsername(request.getUsername());
        if (user == null || !user.isEnabled()
                || user.getPasswordHash() == null || user.getPasswordHash().isEmpty()
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // Empty hash => OAuth-only account; reject with the same generic message to avoid enumeration.
            throw new BadCredentialsException("Invalid username or password");
        }
        String token = tokenService.issue(user.getUid(), user.getUsername(), user.getRoles(), user.getPermissions());
        long expiresIn = props.getJwt().getExpiration().toSeconds();
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", expiresIn));
    }
}

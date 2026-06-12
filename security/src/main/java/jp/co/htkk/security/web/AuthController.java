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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

// NOT @RestController (a @Component stereotype): the app component-scans jp.co.htkk, and this
// controller is registered as a @Bean by the auto-config. @RequestMapping makes it a handler
// without making it a @Component, so it is not registered twice.
@RequestMapping
@ResponseBody
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
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        String token = tokenService.issue(user.getUid(), user.getUsername(), user.getRoles(), user.getPermissions());
        long expiresIn = props.getJwt().getExpiration().toSeconds();
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", expiresIn));
    }
}

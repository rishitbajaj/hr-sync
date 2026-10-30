package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.AuthService;
import com.moveinsync.hrsync.config.AuthSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService auth;
    private final AuthSettings settings;

    public AuthController(AuthService auth, AuthSettings settings) {
        this.auth = auth;
        this.settings = settings;
    }

    @PostMapping("/auth/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        String token = auth.login(request == null ? null : request.username(), request == null ? null : request.password());
        return new TokenResponse(token, settings.getTokenTtl().toSeconds());
    }

    public record TokenRequest(@Size(max = 128) String username, @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "TokenRequest[username=" + username + "]";
        }
    }

    public record TokenResponse(String token, long expiresInSeconds) {
        @Override
        public String toString() {
            return "TokenResponse[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }
}

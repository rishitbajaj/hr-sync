package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.AuthService;
import com.moveinsync.hrsync.auth.AuthenticationRejectedException;
import com.moveinsync.hrsync.config.AuthSettings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    public TokenResponse token(@RequestBody TokenRequest request) {
        String token = auth.login(request == null ? null : request.username(), request == null ? null : request.password());
        return new TokenResponse(token, settings.getTokenTtl().toSeconds());
    }

    @ExceptionHandler(AuthenticationRejectedException.class)
    public ResponseEntity<ApiError> rejected() {
        return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Invalid username or password"));
    }

    public record TokenRequest(String username, String password) {
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

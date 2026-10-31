package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.AuthService;
import com.moveinsync.hrsync.config.AuthSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

    @Operation(summary = "Issue a token", description = "Open. A bad password is 401 UNAUTHORIZED. The token lasts token-ttl, 15 minutes by default.")
    @ApiResponse(responseCode = "200", description = "token and expiresInSeconds.")
    @ApiResponse(responseCode = "401", description = "Invalid username or password.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/auth/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        String token = auth.login(request == null ? null : request.username(), request == null ? null : request.password());
        return new TokenResponse(token, settings.getTokenTtl().toSeconds());
    }

    public record TokenRequest(
            @Schema(example = "admin-1") @Size(max = 128) String username,
            @Schema(example = "password-1") @Size(max = 128) String password
    ) {
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

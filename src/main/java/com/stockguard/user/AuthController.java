package com.stockguard.user;

import com.stockguard.security.JwtTokenService.IssuedToken;
import com.stockguard.user.AuthService.RegisteredUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
@SecurityRequirements
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "register", summary = "Register a new user with the USER role")
    public RegisteredUser register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request.username(), request.password());
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Exchange credentials for a bearer token")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        IssuedToken token = auth.login(request.username(), request.password());
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
    }

    public record RegisterRequest(
            // Must start with a letter or digit, which keeps the reserved "__sim-" namespace out of reach.
            @NotBlank
            @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}$",
                    message = "must be 3-64 letters, digits, dots, underscores or hyphens and start with a letter or digit")
            String username,
            @NotBlank @Size(min = 10, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 256) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
    }
}

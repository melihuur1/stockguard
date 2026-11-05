package com.stockguard.user;

import com.stockguard.audit.AuditEventType;
import com.stockguard.audit.AuditTrail;
import com.stockguard.common.error.ApiException;
import com.stockguard.common.error.ErrorCode;
import com.stockguard.security.JwtTokenService;
import com.stockguard.security.JwtTokenService.IssuedToken;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /** BCrypt only considers the first 72 bytes, so longer passwords would be silently weakened. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokens;
    private final AuditTrail audit;

    public AuthService(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtTokenService tokens,
            AuditTrail audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
        this.audit = audit;
    }

    /** Self-registration can only ever create the USER role. */
    @Transactional
    public RegisteredUser register(String username, String rawPassword) {
        requireBcryptCompatible(rawPassword);
        String normalized = normalize(username);
        UUID id = insert(normalized, rawPassword, Role.USER)
                .orElseThrow(() -> new ApiException(ErrorCode.USERNAME_TAKEN, "This username is already taken."));
        return new RegisteredUser(id, normalized);
    }

    /** Idempotent, because every instance runs it on startup. */
    @Transactional
    public void seedAdmin(String username, String rawPassword) {
        requireBcryptCompatible(rawPassword);
        insert(normalize(username), rawPassword, Role.ADMIN);
    }

    public IssuedToken login(String username, String rawPassword) {
        try {
            Authentication result = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalize(username), rawPassword));
            AppUserDetails user = (AppUserDetails) result.getPrincipal();
            return tokens.issue(user.id(), user.username(), user.role());
        } catch (AuthenticationException ex) {
            // Same answer for an unknown user and a wrong password, so usernames cannot be probed.
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password.");
        }
    }

    private Optional<UUID> insert(String username, String rawPassword, Role role) {
        UUID id = UUID.randomUUID();
        if (users.insertIfAbsent(id, username, passwordEncoder.encode(rawPassword), role.name()) == 0) {
            return Optional.empty();
        }
        audit.record(AuditEventType.USER_REGISTERED, id, "USER", id, Map.of("username", username, "role", role.name()));
        return Optional.of(id);
    }

    private static void requireBcryptCompatible(String rawPassword) {
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must not exceed 72 bytes.");
        }
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public record RegisteredUser(UUID id, String username) {
    }
}

package com.stockguard.security;

import com.stockguard.user.Role;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller, as established by a verified token. */
public record Actor(UUID userId, Role role) {

    public static Actor from(Jwt jwt) {
        return new Actor(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString(JwtTokenService.ROLE_CLAIM)));
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean canAccessResourceOf(UUID ownerId) {
        return isAdmin() || userId.equals(ownerId);
    }
}

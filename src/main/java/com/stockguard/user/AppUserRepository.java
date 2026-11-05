package com.stockguard.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends Repository<AppUser, UUID> {

    Optional<AppUser> findByUsername(String username);

    /** Safe when two requests, or two instances seeding the admin, race for the same username. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO app_users (id, username, password_hash, role, created_at)
            VALUES (:id, :username, :passwordHash, :role, now())
            ON CONFLICT (username) DO NOTHING
            """)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("role") String role);
}

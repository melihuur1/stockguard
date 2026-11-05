package com.stockguard.user;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Adapter between the user model and Spring Security, so the entity stays free of security types. */
record AppUserDetails(UUID id, String username, String passwordHash, Role role) implements UserDetails {

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Service
    static class Lookup implements UserDetailsService {

        private final AppUserRepository users;

        Lookup(AppUserRepository users) {
            this.users = users;
        }

        @Override
        public UserDetails loadUserByUsername(String username) {
            return users.findByUsername(username)
                    .map(user -> new AppUserDetails(user.getId(), user.getUsername(), user.getPasswordHash(), user.getRole()))
                    .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        }
    }
}

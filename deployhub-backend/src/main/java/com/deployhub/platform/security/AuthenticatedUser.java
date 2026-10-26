package com.deployhub.platform.security;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/**
 * The principal stored in the HTTP session after login. The user id here is the only source
 * of "who is asking" for the backend; ids sent by the browser are never trusted.
 */
public class AuthenticatedUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final String displayName;
    private String passwordHash;

    public AuthenticatedUser(Long id, String email, String displayName, String passwordHash) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /** Called by Spring Security after authentication so the hash never sits in the session. */
    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}

package com.deployhub.platform.security;

import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.repositories.UserRepository;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** The current user is whoever the server-side session says is logged in - nothing else. */
@Service
@RequiredArgsConstructor
public class SecurityCurrentUserService implements ICurrentUserService {

    private final UserRepository userRepository;

    @Override
    public Long currentUserId() {
        return currentUser().getId();
    }

    @Override
    public UserMaster currentUserReference() {
        return userRepository.getReferenceById(currentUserId());
    }

    public AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        return user;
    }
}

package com.deployhub.platform.security;

import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.models.enums.AccountStatus;
import com.deployhub.platform.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads accounts for login. Disabled, legacy and missing accounts all look like "not found",
 * so the login response never reveals which of those cases applied; Spring's
 * DaoAuthenticationProvider also runs a dummy password check for them to keep timing similar.
 */
@Service
@RequiredArgsConstructor
public class DeployHubUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public AuthenticatedUser loadUserByUsername(String email) {
        UserMaster user = userRepository.findByEmail(EmailNormalizer.normalize(email))
                .filter(u -> u.getStatus() == AccountStatus.ACTIVE && u.getPasswordHash() != null)
                .orElseThrow(() -> new UsernameNotFoundException("No active account"));
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash());
    }
}

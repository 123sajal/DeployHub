package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.exceptions.ConflictException;
import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.models.DTO.CurrentUserResponseDTO;
import com.deployhub.platform.models.DTO.LoginRequestDTO;
import com.deployhub.platform.models.DTO.RegisterRequestDTO;
import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.models.enums.AccountStatus;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.UserRepository;
import com.deployhub.platform.security.AuthenticatedUser;
import com.deployhub.platform.security.ClientIpResolver;
import com.deployhub.platform.security.LoginThrottleService;
import com.deployhub.platform.security.SecurityCurrentUserService;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.IAuthService;
import com.deployhub.platform.utils.PasswordValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

@Service
public class AuthService implements IAuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final LoginThrottleService throttle;
    private final IAuditService auditService;
    private final SecurityCurrentUserService currentUserService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       SessionAuthenticationStrategy sessionAuthenticationStrategy,
                       SecurityContextRepository securityContextRepository, LoginThrottleService throttle,
                       IAuditService auditService, SecurityCurrentUserService currentUserService,
                       PlatformTransactionManager transactionManager, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.throttle = throttle;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public CurrentUserResponseDTO register(RegisterRequestDTO request, HttpServletRequest httpRequest,
                                           HttpServletResponse httpResponse) {
        String ip = ClientIpResolver.resolve(httpRequest);
        throttle.checkRegistration(ip);
        if (request.password().trim().equalsIgnoreCase(request.email())) {
            throw new FieldValidationException("password", "must not be the same as your email");
        }
        // Without email verification, telling the user "already registered" is unavoidable here;
        // registrations are rate-limited per IP to make bulk probing expensive.
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new ConflictException("An account with this email already exists");
        }

        UserMaster user;
        try {
            user = transactionTemplate.execute(status -> {
                Instant now = clock.instant();
                UserMaster created = new UserMaster();
                created.setEmail(request.email());
                created.setDisplayName(request.displayName());
                created.setPasswordHash(passwordEncoder.encode(request.password()));
                created.setStatus(AccountStatus.ACTIVE);
                created.setCreatedAt(now);
                created.setUpdatedAt(now);
                created.setLastLoginAt(now);
                userRepository.saveAndFlush(created);
                auditService.record(AuditEventType.REGISTERED, created.getId(), created.getEmail(), ip, null);
                return created;
            });
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An account with this email already exists"); // lost a race with a twin request
        }

        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName(), null);
        startSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()),
                httpRequest, httpResponse);
        return toResponse(principal);
    }

    @Override
    public CurrentUserResponseDTO login(LoginRequestDTO request, HttpServletRequest httpRequest,
                                        HttpServletResponse httpResponse) {
        String ip = ClientIpResolver.resolve(httpRequest);
        throttle.checkLogin(request.email(), ip);

        Authentication authentication;
        try {
            if (!PasswordValidator.fitsBcrypt(request.password())) {
                throw new BadCredentialsException("Password longer than any valid password");
            }
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (BadCredentialsException e) {
            auditService.record(AuditEventType.LOGIN_FAILED, null, request.email(), ip, null);
            throw new BadCredentialsException("Invalid email or password");
        } catch (InternalAuthenticationServiceException e) {
            // e.g. the database is down while loading the account: surface the real cause (-> 503).
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }

        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        startSession(authentication, httpRequest, httpResponse);
        transactionTemplate.executeWithoutResult(status -> {
            userRepository.findById(principal.getId()).ifPresent(user -> user.setLastLoginAt(clock.instant()));
            auditService.record(AuditEventType.LOGIN_SUCCEEDED, principal.getId(), principal.getEmail(), ip, null);
        });
        return toResponse(principal);
    }

    @Override
    public CurrentUserResponseDTO currentUser() {
        return toResponse(currentUserService.currentUser());
    }

    /**
     * Rotates the session id and CSRF token (session-fixation defence), then stores the
     * authenticated principal in the server-side session.
     */
    private void startSession(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        // Issue the rotated XSRF-TOKEN cookie in this response, so the very next POST works.
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        }
    }

    private static CurrentUserResponseDTO toResponse(AuthenticatedUser user) {
        return new CurrentUserResponseDTO(user.getId(), user.getEmail(), user.getDisplayName());
    }
}

package com.deployhub.platform.config;

import com.deployhub.platform.security.CsrfCookieFilter;
import com.deployhub.platform.security.ProblemDetailSecurityHandlers;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.security.AuthenticatedUser;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.security.ClientIpResolver;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.List;

/**
 * Session-based authentication for the Angular SPA:
 * <ul>
 *   <li>Login creates a server-side session; the browser only holds the HttpOnly JSESSIONID cookie.</li>
 *   <li>CSRF: Angular echoes the XSRF-TOKEN cookie in an X-XSRF-TOKEN header on every write.</li>
 *   <li>Everything under /api requires a session except health, register, login and csrf.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // "{bcrypt}..." prefixed hashes; lets us move to a stronger algorithm later without a reset.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(DeployHubProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse(); // Angular must read it
        repository.setCookieCustomizer(cookie -> cookie
                .sameSite("Lax")
                .secure(properties.security().cookieSecure())
                .path("/"));
        return repository;
    }

    /** Applied on login: new session id (blocks session fixation) and a fresh CSRF token. */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(CookieCsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    /** The HTTP filter chain only exists in a web application (not in e.g. non-web restart tests). */
    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebSecurity {

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                       CookieCsrfTokenRepository csrfTokenRepository,
                                                       SecurityContextRepository securityContextRepository,
                                                       IAuditService auditService, DeployHubProperties properties) {
            http
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                    "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                            .requestMatchers("/api/health", "/actuator/health", "/actuator/health/liveness",
                                    "/actuator/health/readiness", "/error").permitAll()
                            // Metrics reveal load and internals: only the monitoring network may read them.
                            .requestMatchers("/actuator/prometheus").access(fromNetworks(properties.metrics().allowedIps()))
                            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                            // Only exists when the local profile enables the H2 console.
                            .requestMatchers("/h2-console/**").permitAll()
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().denyAll())
                    .csrf(csrf -> csrf
                            .spa()
                            .csrfTokenRepository(csrfTokenRepository)
                            .ignoringRequestMatchers("/h2-console/**"))
                    .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                    .securityContext(context -> context.securityContextRepository(securityContextRepository))
                    .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                    .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                    .exceptionHandling(errors -> errors
                            .authenticationEntryPoint(ProblemDetailSecurityHandlers.unauthorized())
                            .accessDeniedHandler(ProblemDetailSecurityHandlers.forbidden()))
                    .logout(logout -> logout
                            .logoutUrl("/api/auth/logout")
                            .addLogoutHandler(auditLogout(auditService))
                            .logoutSuccessHandler(issueFreshCsrfToken(csrfTokenRepository))
                            .deleteCookies("JSESSIONID"))
                    .requestCache(AbstractHttpConfigurer::disable)
                    .formLogin(AbstractHttpConfigurer::disable)
                    .httpBasic(AbstractHttpConfigurer::disable);
            return http.build();
        }
    }

    /** Allows a request only when its client IP is inside one of the given CIDR ranges. */
    private static AuthorizationManager<RequestAuthorizationContext> fromNetworks(List<String> cidrs) {
        List<IpAddressMatcher> networks = cidrs.stream().map(String::trim).filter(c -> !c.isEmpty())
                .map(IpAddressMatcher::new).toList();
        return (authentication, context) -> new AuthorizationDecision(
                networks.stream().anyMatch(network -> network.matches(context.getRequest())));
    }

    /**
     * Logout deletes the old CSRF cookie (it belonged to the ended session). Hand out a new one in
     * the same response, so the browser can log in again straight away without reloading.
     */
    private static LogoutSuccessHandler issueFreshCsrfToken(CookieCsrfTokenRepository csrfTokenRepository) {
        return (request, response, authentication) -> {
            csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
            response.setStatus(HttpStatus.NO_CONTENT.value());
        };
    }

    private static LogoutHandler auditLogout(IAuditService auditService) {
        return (request, response, authentication) -> {
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
                try {
                    auditService.record(AuditEventType.LOGGED_OUT, user.getId(), user.getEmail(),
                            ClientIpResolver.resolve(request), null);
                } catch (RuntimeException e) {
                    // Logging out must still work when the database is down; the session is destroyed regardless.
                    LoggerFactory.getLogger(SecurityConfig.class).warn("Could not audit logout: {}", e.getMessage());
                }
            }
        };
    }
}

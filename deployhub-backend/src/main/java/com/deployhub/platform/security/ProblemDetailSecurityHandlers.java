package com.deployhub.platform.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.nio.charset.StandardCharsets;

/** Errors raised inside the security filter chain, in the same RFC 9457 shape as the rest of the API. */
public final class ProblemDetailSecurityHandlers {

    private ProblemDetailSecurityHandlers() {
    }

    public static AuthenticationEntryPoint unauthorized() {
        return (request, response, ex) -> write(response, HttpStatus.UNAUTHORIZED, "Authentication required",
                "Log in to access this resource.");
    }

    public static AccessDeniedHandler forbidden() {
        // Most often a missing/expired CSRF token on a POST/PUT/DELETE.
        return (request, response, ex) -> write(response, HttpStatus.FORBIDDEN, "Forbidden",
                "The request was rejected. Reload the page and try again.");
    }

    private static void write(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":"
                + status.value() + ",\"detail\":\"" + detail + "\"}");
    }
}

package com.deployhub.platform.controllers;

import com.deployhub.platform.models.DTO.CurrentUserResponseDTO;
import com.deployhub.platform.models.DTO.LoginRequestDTO;
import com.deployhub.platform.models.DTO.RegisterRequestDTO;
import com.deployhub.platform.services.Interface.IAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/auth/logout is handled by Spring Security's logout filter (see SecurityConfig). */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final IAuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public CurrentUserResponseDTO register(@Valid @RequestBody RegisterRequestDTO request,
                                           HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return authService.register(request, httpRequest, httpResponse);
    }

    @PostMapping("/login")
    public CurrentUserResponseDTO login(@Valid @RequestBody LoginRequestDTO request,
                                        HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return authService.login(request, httpRequest, httpResponse);
    }

    /**
     * Public. Returns nothing; its only job is to make the response carry a fresh XSRF-TOKEN cookie
     * (written by CsrfCookieFilter), e.g. when the browser lost its token and a write was rejected.
     */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf() {
        // intentionally empty
    }

    @GetMapping("/me")
    public CurrentUserResponseDTO me() {
        return authService.currentUser();
    }
}

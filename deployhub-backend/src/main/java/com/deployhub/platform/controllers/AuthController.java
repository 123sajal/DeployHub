package com.deployhub.platform.controllers;

import com.deployhub.platform.models.DTO.CurrentUserResponseDTO;
import com.deployhub.platform.models.DTO.ForgotPasswordRequestDTO;
import com.deployhub.platform.models.DTO.LoginRequestDTO;
import com.deployhub.platform.models.DTO.ProfileUpdateRequestDTO;
import com.deployhub.platform.models.DTO.RegisterRequestDTO;
import com.deployhub.platform.models.DTO.ResetPasswordRequestDTO;
import com.deployhub.platform.security.ClientIpResolver;
import com.deployhub.platform.services.Interface.IAuthService;
import com.deployhub.platform.services.Interface.IPasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final IPasswordResetService passwordResetService;

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

    /** Public. Always 202, whether or not the email has an account (the answer must not tell). */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequestDTO request, HttpServletRequest httpRequest) {
        passwordResetService.requestCode(request, ClientIpResolver.resolve(httpRequest));
    }

    /** Public. Sets a new password with the emailed code; the user then logs in with it. */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequestDTO request, HttpServletRequest httpRequest) {
        passwordResetService.resetPassword(request, ClientIpResolver.resolve(httpRequest));
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

    @PatchMapping("/me")
    public CurrentUserResponseDTO updateMe(@Valid @RequestBody ProfileUpdateRequestDTO request) {
        return authService.updateProfile(request);
    }
}

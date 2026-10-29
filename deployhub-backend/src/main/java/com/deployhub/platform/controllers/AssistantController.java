package com.deployhub.platform.controllers;

import com.deployhub.platform.models.DTO.AssistantChatRequestDTO;
import com.deployhub.platform.models.DTO.AssistantExplainRequestDTO;
import com.deployhub.platform.models.DTO.AssistantReplyResponseDTO;
import com.deployhub.platform.models.DTO.AssistantStatusResponseDTO;
import com.deployhub.platform.models.DTO.EmailCodeRequestDTO;
import com.deployhub.platform.models.DTO.EmailCodeVerifyRequestDTO;
import com.deployhub.platform.models.DTO.EmailProofResponseDTO;
import com.deployhub.platform.models.DTO.SignupCheckRequestDTO;
import com.deployhub.platform.models.DTO.SignupCheckResponseDTO;
import com.deployhub.platform.security.ClientIpResolver;
import com.deployhub.platform.services.Interface.IAssistantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI assistant. Public (before signing in): status, the sign-up checks and codes, and chat. Explaining
 * failures needs a session.
 */
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final IAssistantService assistantService;

    @GetMapping("/status")
    public AssistantStatusResponseDTO status() {
        return assistantService.status();
    }

    @PostMapping("/signup/check")
    public SignupCheckResponseDTO checkSignup(@Valid @RequestBody SignupCheckRequestDTO request) {
        return assistantService.checkSignup(request);
    }

    @PostMapping("/signup/email-code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendSignupCode(@Valid @RequestBody EmailCodeRequestDTO request, HttpServletRequest httpRequest) {
        assistantService.sendSignupCode(request, ClientIpResolver.resolve(httpRequest));
    }

    @PostMapping("/signup/email-code/verify")
    public EmailProofResponseDTO verifySignupCode(@Valid @RequestBody EmailCodeVerifyRequestDTO request,
                                                  HttpServletRequest httpRequest) {
        return assistantService.verifySignupCode(request, ClientIpResolver.resolve(httpRequest));
    }

    @PostMapping("/chat")
    public AssistantReplyResponseDTO chat(@Valid @RequestBody AssistantChatRequestDTO request,
                                          HttpServletRequest httpRequest) {
        return assistantService.chat(request, ClientIpResolver.resolve(httpRequest));
    }

    @PostMapping("/explain")
    public AssistantReplyResponseDTO explain(@Valid @RequestBody AssistantExplainRequestDTO request) {
        return assistantService.explain(request);
    }
}

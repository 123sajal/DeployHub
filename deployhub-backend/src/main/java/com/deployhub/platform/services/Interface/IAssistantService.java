package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.AssistantChatRequestDTO;
import com.deployhub.platform.models.DTO.AssistantExplainRequestDTO;
import com.deployhub.platform.models.DTO.AssistantReplyResponseDTO;
import com.deployhub.platform.models.DTO.AssistantStatusResponseDTO;
import com.deployhub.platform.models.DTO.EmailCodeRequestDTO;
import com.deployhub.platform.models.DTO.EmailCodeVerifyRequestDTO;
import com.deployhub.platform.models.DTO.EmailProofResponseDTO;
import com.deployhub.platform.models.DTO.SignupCheckRequestDTO;
import com.deployhub.platform.models.DTO.SignupCheckResponseDTO;

public interface IAssistantService {

    AssistantStatusResponseDTO status();

    /** Checks one sign-up answer (name or email) and says what's wrong or what comes next. No AI needed. */
    SignupCheckResponseDTO checkSignup(SignupCheckRequestDTO request);

    /** Emails a sign-up code; 409 when the email already has an account. */
    void sendSignupCode(EmailCodeRequestDTO request, String ip);

    /** Checks the sign-up code and returns a proof to send with the registration. */
    EmailProofResponseDTO verifySignupCode(EmailCodeVerifyRequestDTO request, String ip);

    /** Answers a question, signed in or not (limits are per user, or per network before signing in). */
    AssistantReplyResponseDTO chat(AssistantChatRequestDTO request, String ip);

    /** Explains a failed deployment or an error the user saw. */
    AssistantReplyResponseDTO explain(AssistantExplainRequestDTO request);
}

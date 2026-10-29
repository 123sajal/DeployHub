package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.EmailCodeRequestDTO;
import com.deployhub.platform.models.DTO.EmailCodeVerifyRequestDTO;
import com.deployhub.platform.models.DTO.StudentVerificationResponseDTO;
import org.springframework.web.multipart.MultipartFile;

public interface IStudentService {

    /** Checks a student ID photo with the AI. The photo is never stored. */
    StudentVerificationResponseDTO verifyIdCard(MultipartFile photo, boolean consent);

    /** Emails a code to a college address (e.g. ending in .ac.in). */
    void sendCollegeEmailCode(EmailCodeRequestDTO request, String ip);

    StudentVerificationResponseDTO verifyCollegeEmail(EmailCodeVerifyRequestDTO request, String ip);
}

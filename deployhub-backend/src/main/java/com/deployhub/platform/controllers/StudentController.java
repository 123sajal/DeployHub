package com.deployhub.platform.controllers;

import com.deployhub.platform.models.DTO.EmailCodeRequestDTO;
import com.deployhub.platform.models.DTO.EmailCodeVerifyRequestDTO;
import com.deployhub.platform.models.DTO.StudentVerificationResponseDTO;
import com.deployhub.platform.security.ClientIpResolver;
import com.deployhub.platform.services.Interface.IStudentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The student check behind the student discount (Settings > Student discount). */
@RestController
@RequestMapping("/api/students/verify")
@RequiredArgsConstructor
public class StudentController {

    private final IStudentService studentService;

    /** A photo of the student ID card (JPG or PNG). It is checked and forgotten, never stored. */
    @PostMapping(path = "/id-card", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StudentVerificationResponseDTO verifyIdCard(@RequestPart("file") MultipartFile file,
                                                       @RequestParam(defaultValue = "false") boolean consent) {
        return studentService.verifyIdCard(file, consent);
    }

    @PostMapping("/college-email/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendCollegeEmailCode(@Valid @RequestBody EmailCodeRequestDTO request, HttpServletRequest httpRequest) {
        studentService.sendCollegeEmailCode(request, ClientIpResolver.resolve(httpRequest));
    }

    @PostMapping("/college-email")
    public StudentVerificationResponseDTO verifyCollegeEmail(@Valid @RequestBody EmailCodeVerifyRequestDTO request,
                                                             HttpServletRequest httpRequest) {
        return studentService.verifyCollegeEmail(request, ClientIpResolver.resolve(httpRequest));
    }
}

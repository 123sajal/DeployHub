package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.StudentProof;

import java.time.LocalDate;

/**
 * @param verified   the student discount now applies
 * @param validUntil last day it applies (India time)
 * @param method     how the student was verified
 * @param message    what to tell the student
 */
public record StudentVerificationResponseDTO(boolean verified, LocalDate validUntil, StudentProof method,
                                             String message) {
}

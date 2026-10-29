package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.Occupation;
import com.deployhub.platform.models.enums.StudentProof;

import java.time.LocalDate;

/**
 * @param occupation           student or working professional (null = not told)
 * @param emailVerified        the email was proven with a code at sign-up
 * @param studentVerifiedUntil last day the student discount applies, or null (past dates mean it expired)
 * @param studentVerifiedBy    how the student was verified, or null
 */
public record CurrentUserResponseDTO(Long id, String email, String displayName, Occupation occupation,
                                     boolean emailVerified, LocalDate studentVerifiedUntil,
                                     StudentProof studentVerifiedBy) {
}

package com.deployhub.platform.models.DTO;

import java.time.Instant;

/** @param proof send back as emailProof when signing up; works for this email until expiresAt */
public record EmailProofResponseDTO(String email, String proof, Instant expiresAt) {
}

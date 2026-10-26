package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.PublicLinkResponseDTO;

/** Opt-in public internet links per project (off by default; a private backend never needs one). */
public interface IProjectPublicLinkService {

    /** The current state, with the link re-read from the running tunnel. */
    PublicLinkResponseDTO get(Long projectId);

    /** Starts the public link (the project must be live). Can take up to a minute the first time. */
    PublicLinkResponseDTO enable(Long projectId);

    PublicLinkResponseDTO disable(Long projectId);
}

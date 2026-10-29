package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.CurrentUserResponseDTO;
import com.deployhub.platform.models.DTO.LoginRequestDTO;
import com.deployhub.platform.models.DTO.ProfileUpdateRequestDTO;
import com.deployhub.platform.models.DTO.RegisterRequestDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface IAuthService {

    /** Creates the account and logs the new user in. */
    CurrentUserResponseDTO register(RegisterRequestDTO request, HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse);

    CurrentUserResponseDTO login(LoginRequestDTO request, HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse);

    CurrentUserResponseDTO currentUser();

    /** Changes what the signed-in user told the assistant (student or working professional). */
    CurrentUserResponseDTO updateProfile(ProfileUpdateRequestDTO request);
}

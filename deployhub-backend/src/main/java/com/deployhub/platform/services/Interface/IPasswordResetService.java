package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.ForgotPasswordRequestDTO;
import com.deployhub.platform.models.DTO.ResetPasswordRequestDTO;

public interface IPasswordResetService {

    /**
     * Emails a one-time code if an active account has this address. Behaves the same either way, so
     * the answer never reveals who has an account.
     */
    void requestCode(ForgotPasswordRequestDTO request, String ip);

    /** Sets the new password when the code is right, unused, unexpired and not out of attempts. */
    void resetPassword(ResetPasswordRequestDTO request, String ip);
}

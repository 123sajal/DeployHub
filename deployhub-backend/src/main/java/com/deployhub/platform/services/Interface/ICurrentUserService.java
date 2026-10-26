package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.entities.UserMaster;

/** Who is making the current request, taken from the server-side login session (see SecurityCurrentUserService). */
public interface ICurrentUserService {

    Long currentUserId();

    UserMaster currentUserReference();
}

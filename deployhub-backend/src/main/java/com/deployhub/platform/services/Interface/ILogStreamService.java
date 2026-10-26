package com.deployhub.platform.services.Interface;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ILogStreamService {

    /**
     * Opens a Server-Sent Events stream of a deployment's logs, starting after log id {@code afterCursor}.
     * Checks ownership and the stream limits on the calling (request) thread.
     */
    SseEmitter open(Long deploymentId, long afterCursor);
}

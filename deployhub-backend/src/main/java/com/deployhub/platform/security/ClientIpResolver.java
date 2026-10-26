package com.deployhub.platform.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Uses the TCP peer address only. X-Forwarded-For is deliberately NOT trusted: any client can
 * set it to dodge rate limits. Behind a reverse proxy (Milestone 7) configure
 * server.forward-headers-strategy so the container rewrites remoteAddr from the trusted proxy.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip.substring(0, Math.min(ip.length(), 45));
    }
}

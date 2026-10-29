package com.deployhub.platform.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that decides how DeployHub runs git against user-supplied repositories (deploys and repository
 * detection): https only, no credentials from anywhere, no hooks, no symlinks, no prompts.
 */
public final class GitSafety {

    private GitSafety() {
    }

    /** @param allowLocalRepositories tests only: also accept file:// repositories */
    public static List<String> flags(boolean allowLocalRepositories) {
        List<String> flags = new ArrayList<>(List.of(
                "-c", "protocol.allow=never", "-c", "protocol.https.allow=always",
                "-c", "credential.helper=", "-c", "core.askPass=",
                "-c", "core.symlinks=false", "-c", "core.hooksPath=/dev/null",
                "-c", "advice.detachedHead=false"));
        if (allowLocalRepositories) {
            flags.addAll(List.of("-c", "protocol.file.allow=always"));
        }
        return List.copyOf(flags);
    }

    /**
     * Credentials for one private repository, as git configuration in environment variables (GIT_CONFIG_COUNT/KEY/VALUE):
     * an Authorization header sent only to that repository's host. Never on the command line, so it can't show up in
     * process lists or logs. Empty when there is no token.
     */
    public static Map<String, String> authEnvironment(String repositoryUrl, String token) {
        if (token == null || token.isBlank() || repositoryUrl == null) {
            return Map.of();
        }
        java.net.URI uri = java.net.URI.create(repositoryUrl.trim());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        String user = switch (host) {
            case "gitlab.com" -> "oauth2";
            case "bitbucket.org" -> "x-token-auth";
            default -> "x-access-token"; // GitHub
        };
        String basic = java.util.Base64.getEncoder().encodeToString((user + ":" + token.trim())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, String> env = new HashMap<>();
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "http." + uri.getScheme() + "://" + host + "/.extraheader");
        env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        return env;
    }

    /**
     * No credentials from anywhere: system/global config, askpass helpers (e.g. an IDE's), or a prompt.
     * A null value means "remove this variable".
     */
    public static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        System.getenv().keySet().stream()
                .filter(name -> name.startsWith("GIT_") || name.startsWith("VSCODE_GIT") || name.equals("SSH_ASKPASS"))
                .forEach(name -> env.put(name, null));
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_LFS_SKIP_SMUDGE", "1");
        return env;
    }
}

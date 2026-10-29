package com.deployhub.platform.execution;

import com.deployhub.platform.models.enums.ApplicationType;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Writes the Dockerfile for projects that don't bring their own. One stage, kept simple on purpose:
 * copy the repository, run the build command, start with the start command. With an app folder, the commands
 * run in that folder; the whole repository is still copied, so the app can use files outside it.
 *
 * <ul>
 *   <li>The build command runs inside Docker's build sandbox, never on this machine.</li>
 *   <li>The start command is written in exec form ({@code CMD ["sh", "-c", "..."]}), JSON-encoded, so its
 *       quotes and special characters can't break the Dockerfile. Line breaks are rejected at input.</li>
 *   <li>{@code PORT} (and {@code SERVER_PORT} for Spring Boot) tell the app where to listen.</li>
 *   <li>The build runs as root with root's own home folder; {@code HOME=/tmp} is set only after it, for the app.
 *       Otherwise what the build leaves in its home (e.g. npm's cache) would be root-owned in the app's home, and
 *       the app's unprivileged user couldn't use it ({@code npx} failed with EACCES).</li>
 *   <li>{@code npm ci} in the build command becomes {@code (npm ci || npm install)}: a lock file made by another npm
 *       version than the image's (e.g. npm 11 on a Mac, npm 10 here) makes {@code npm ci} refuse ("EUSAGE ...
 *       Missing: ... from lock file"); users shouldn't need to know that, so a normal install takes over.</li>
 *   <li>The app folder belongs to the unprivileged user the app runs as, so it can create files and folders
 *       there (e.g. {@code uploads/}), as on other hosting platforms. They are lost when the container is replaced.</li>
 * </ul>
 */
public final class DockerfileGenerator {

    /** Bump when the template changes, so cached images built from the old template aren't reused. */
    static final String TEMPLATE_VERSION = "4";

    /** The unprivileged user the app runs as (docker run --user). It owns the app folder, so the app can write there. */
    static final String APP_USER = "10001:10001";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DockerfileGenerator() {
    }

    /** One command of a command line and the separator after it ("&&", "||", ";" or "" at the end). */
    private record Part(String command, String separator) {
    }

    /**
     * "npm ci --omit=dev && npm run build" -> "(npm ci --omit=dev || npm install --omit=dev) && npm run build".
     * Only a whole command that starts with "npm ci" is rewritten, with all its arguments copied to npm install; a
     * command that is already part of an || chain, or anything inside quotes, parentheses or a pipe, is left alone.
     */
    static String withNpmFallback(String commandLine) {
        // Quotes, subshells (also an existing "(npm ci || npm install)"), a single pipe, $ or \: too risky to rewrite.
        String withoutOr = commandLine.replace("||", "");
        if (!commandLine.contains("npm ci") || withoutOr.matches("(?s).*['\"`()|\\\\$].*")) {
            return commandLine;
        }
        List<Part> parts = new java.util.ArrayList<>();
        java.util.regex.Matcher sep = java.util.regex.Pattern.compile("&&|\\|\\||;").matcher(commandLine);
        int from = 0;
        while (sep.find()) {
            parts.add(new Part(commandLine.substring(from, sep.start()), sep.group()));
            from = sep.end();
        }
        parts.add(new Part(commandLine.substring(from), ""));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            String trimmed = part.command().trim();
            boolean inOrChain = "||".equals(part.separator()) || i > 0 && "||".equals(parts.get(i - 1).separator());
            String rewritten = part.command();
            if (!inOrChain && (trimmed.equals("npm ci") || trimmed.startsWith("npm ci "))) {
                String args = trimmed.substring("npm ci".length());
                String lead = part.command().substring(0, part.command().indexOf("npm ci"));
                String tail = part.command().substring(part.command().indexOf(trimmed) + trimmed.length());
                rewritten = lead + "(npm ci" + args + " || npm install" + args + ")" + tail;
            }
            out.append(rewritten).append(part.separator());
        }
        return out.toString();
    }

    public static String baseImage(ApplicationType type) {
        return switch (type) {
            case JAVA_SPRING_BOOT -> "eclipse-temurin:21-jdk";
            case NODE_JS, STATIC_SITE -> "node:22-alpine";
            case PYTHON -> "python:3.12-slim";
            case DOCKERFILE -> throw new IllegalArgumentException("Dockerfile projects use their own Dockerfile");
        };
    }

    public static String generate(ApplicationType type, String buildCommand, String startCommand, int port) {
        return generate(type, buildCommand, startCommand, port, null);
    }

    /** @param rootDirectory folder (validated: letters, digits, . _ - and /) the commands run in; null = /app */
    public static String generate(ApplicationType type, String buildCommand, String startCommand, int port,
                                  String rootDirectory) {
        StringBuilder file = new StringBuilder()
                .append("# Generated by DeployHub for a ").append(type).append(" project.\n")
                .append("FROM ").append(baseImage(type)).append('\n')
                .append("WORKDIR /app\n")
                .append("RUN chown ").append(APP_USER).append(" /app\n")
                .append("COPY --chown=").append(APP_USER).append(" . .\n");
        if (rootDirectory != null) {
            file.append("WORKDIR /app/").append(rootDirectory).append('\n');
        }
        if (buildCommand != null && !buildCommand.isBlank()) {
            file.append("ENV PORT=").append(port).append(" SERVER_PORT=").append(port).append('\n')
                    .append("RUN ").append(withNpmFallback(buildCommand.trim())).append('\n');
        }
        file.append("ENV PORT=").append(port).append(" SERVER_PORT=").append(port).append(" HOME=/tmp");
        if (type == ApplicationType.PYTHON) {
            file.append(" PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1");
        }
        file.append('\n')
                .append("EXPOSE ").append(port).append('\n')
                .append("CMD ").append(JSON.writeValueAsString(List.of("sh", "-c", startCommand.trim()))).append('\n');
        return file.toString();
    }
}

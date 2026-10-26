package com.deployhub.platform.execution;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.worker.DeploymentStepException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Real deployments (Milestone 6): fetch the source with git, build an image with Docker, run it in a
 * resource-limited container and wait for it to answer HTTP.
 *
 * <p><b>Read-only towards the outside world.</b> git only fetches (no push, no webhooks), from public
 * repositories only: every credential source is switched off (no system/global git config, so no macOS
 * keychain helper; no askpass; no terminal prompt), so a private repository fails instead of quietly
 * using this machine's GitHub login.
 *
 * <p><b>Isolation.</b> The project's build command runs inside Docker's build sandbox, never on this
 * machine. The app container gets memory/CPU/process limits, drops all Linux capabilities, can't gain
 * new privileges, runs as an unprivileged user (generated images) and is published on 127.0.0.1 only.
 *
 * <p><b>Fixed addresses.</b> Apps join the {@code deployhub-net} network. Each project gets a small nginx
 * container ("edge") on a fixed port that forwards to the project's live app and, by path prefix, to other
 * projects' live apps; it is rewritten and reloaded on every go-live, so the address never changes.
 *
 * <p><b>Environment variables</b> are passed as {@code -e NAME} with the value in the docker CLI's own
 * environment, so values never appear on a command line (visible to other processes) or in logs.
 *
 * <p><b>Idempotent.</b> Every step can run again after a crash: the container name is derived from the
 * deployment id and removed before starting, and images are tagged by commit + settings, so a repeated
 * build (or a rollback to a commit that was built before) reuses the existing image.
 */
@Slf4j
@Component
public class DockerDeploymentExecutor implements DeploymentExecutor {

    private static final Duration QUICK = Duration.ofSeconds(60);
    private static final Duration PROBE_INTERVAL = Duration.ofSeconds(1);
    private static final int MAX_PORT_TRIES = 25;
    private static final int MAX_REASON = 800;
    static final String NETWORK = "deployhub-net";

    private final CommandRunner runner;
    private final AppProbe probe;
    private final DeployHubProperties.Execution settings;
    private final String proxyImage;
    private final String tunnelImage;
    private final List<String> gitSafetyFlags;

    @Autowired
    public DockerDeploymentExecutor(CommandRunner runner, AppProbe probe, DeployHubProperties properties) {
        this(runner, probe, properties.execution(), properties.gateway().proxyImage(),
                properties.gateway().tunnelImage(), false);
    }

    /** Tests only, with the default proxy image. */
    DockerDeploymentExecutor(CommandRunner runner, AppProbe probe, DeployHubProperties.Execution settings,
                             boolean allowLocalRepositories) {
        this(runner, probe, settings, "nginx:1.27-alpine", "cloudflare/cloudflared:latest", allowLocalRepositories);
    }

    /** @param allowLocalRepositories tests only: also accept file:// repositories */
    DockerDeploymentExecutor(CommandRunner runner, AppProbe probe, DeployHubProperties.Execution settings,
                             String proxyImage, String tunnelImage, boolean allowLocalRepositories) {
        this.runner = runner;
        this.probe = probe;
        this.settings = settings;
        this.proxyImage = proxyImage;
        this.tunnelImage = tunnelImage;
        List<String> flags = new ArrayList<>(List.of(
                "-c", "protocol.allow=never", "-c", "protocol.https.allow=always",
                "-c", "credential.helper=", "-c", "core.askPass=",
                "-c", "core.symlinks=false", "-c", "core.hooksPath=/dev/null",
                "-c", "advice.detachedHead=false"));
        if (allowLocalRepositories) {
            flags.addAll(List.of("-c", "protocol.file.allow=always"));
        }
        this.gitSafetyFlags = List.copyOf(flags);
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    // ---------------------------------------------------------------- build

    @Override
    public BuildResult build(DeploymentSpec spec, ExecutionContext context) {
        Path root = Path.of(settings.workspace());
        Path source = root.resolve("d" + spec.deploymentId() + "-a" + spec.attempt());
        Path generatedDockerfile = root.resolve("d" + spec.deploymentId() + "-a" + spec.attempt() + ".Dockerfile");
        try {
            deleteQuietly(source);
            Files.createDirectories(source);
            String commit = fetchSource(spec, source, context);
            String image = imageRef(spec, commit);
            if (docker(List.of("image", "inspect", "--format", "{{.Id}}", image), QUICK, context, line -> { }).ok()) {
                context.info("Reusing image " + image + ": it was already built from this commit with the same settings");
                return new BuildResult(commit, image);
            }

            Path dockerfile;
            if (spec.appType() == ApplicationType.DOCKERFILE) {
                dockerfile = source.resolve("Dockerfile");
                if (!Files.isRegularFile(dockerfile)) {
                    throw DeploymentStepException.permanent(
                            "No Dockerfile at the root of the repository (the project type is Dockerfile)");
                }
                context.info("Building image " + image + " from the repository's Dockerfile");
            } else {
                if (spec.startCommand() == null || spec.startCommand().isBlank()) {
                    throw DeploymentStepException.permanent("The project has no start command");
                }
                String content = DockerfileGenerator.generate(spec.appType(), spec.buildCommand(), spec.startCommand(),
                        spec.appPort());
                Files.writeString(generatedDockerfile, content, StandardCharsets.UTF_8);
                dockerfile = generatedDockerfile;
                context.info("Building image " + image + " with this generated Dockerfile:");
                content.lines().forEach(line -> context.info("    " + line));
            }

            CommandRunner.Result result = docker(List.of("build", "--progress=plain",
                    "--label", "deployhub.project=" + spec.projectId(),
                    "--label", "deployhub.deployment=" + spec.deploymentId(),
                    "-t", image, "-f", dockerfile.toString(), source.toString()),
                    settings.buildTimeout(), context, context::output);
            if (!result.ok()) {
                throw dockerFailure("The build", result, settings.buildTimeout());
            }
            context.info("Image built: " + image);
            return new BuildResult(commit, image);
        } catch (IOException e) {
            throw DeploymentStepException.transientError("Could not prepare the build folder: " + e.getMessage());
        } finally {
            deleteQuietly(source);
            deleteQuietly(generatedDockerfile);
        }
    }

    /** Shallow, read-only fetch of exactly one branch, tag or commit. Returns the commit SHA checked out. */
    private String fetchSource(DeploymentSpec spec, Path dir, ExecutionContext context) {
        String ref = spec.ref();
        if (ref == null || ref.isBlank() || ref.startsWith("-")) {
            throw DeploymentStepException.permanent("Invalid branch or commit: '" + ref + "'");
        }
        context.info("Fetching " + spec.repositoryUrl() + " @ " + ref + " (read-only, public repositories only)");
        CommandRunner.Result init = git(List.of("init", "-q", dir.toString()), dir.getParent(), context, line -> { });
        if (!init.ok()) {
            throw DeploymentStepException.transientError("git init failed: " + lastLine(init));
        }
        CommandRunner.Result fetch = git(List.of("-C", dir.toString(), "fetch", "--depth", "1", "--no-tags",
                "--end-of-options", spec.repositoryUrl(), ref), dir, context, context::output);
        if (!fetch.ok()) {
            throw gitFailure(fetch, ref);
        }
        CommandRunner.Result checkout = git(List.of("-C", dir.toString(), "checkout", "-q", "--detach", "FETCH_HEAD"),
                dir, context, context::output);
        if (!checkout.ok()) {
            throw DeploymentStepException.permanent("Could not check out the source: " + lastLine(checkout));
        }
        List<String> head = new ArrayList<>();
        CommandRunner.Result revParse = git(List.of("-C", dir.toString(), "rev-parse", "HEAD"), dir, context, head::add);
        String commit = head.isEmpty() ? "" : head.getLast().trim();
        if (!revParse.ok() || !commit.matches("[0-9a-f]{40}")) {
            throw DeploymentStepException.transientError("Could not read the fetched commit: " + lastLine(revParse));
        }
        deleteQuietly(dir.resolve(".git")); // the image doesn't need git metadata
        context.info("Fetched commit " + commit);
        return commit;
    }

    private CommandRunner.Result git(List<String> args, Path workingDir, ExecutionContext context,
                                     Consumer<String> onLine) {
        List<String> command = new ArrayList<>();
        command.add(settings.gitCommand());
        command.addAll(gitSafetyFlags);
        command.addAll(args);
        try {
            return runner.run(command, gitEnvironment(), workingDir, settings.cloneTimeout(), context, onLine);
        } catch (CommandRunner.StartException e) {
            throw DeploymentStepException.permanent("git is not installed or not on the PATH: " + e.getMessage());
        }
    }

    /** No credentials from anywhere: system/global config, askpass helpers (e.g. an IDE's), or a prompt. */
    private static Map<String, String> gitEnvironment() {
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

    private DeploymentStepException gitFailure(CommandRunner.Result result, String ref) {
        String text = result.tailText().toLowerCase(Locale.ROOT);
        if (result.timedOut()) {
            return DeploymentStepException.transientError("Fetching the repository took longer than "
                    + settings.cloneTimeout().toSeconds() + " s");
        }
        if (text.contains("couldn't find remote ref") || text.contains("not our ref")
                || text.contains("unadvertised object") || text.contains("bad object")) {
            return DeploymentStepException.permanent("Branch, tag or commit '" + ref + "' was not found in the "
                    + "repository (use a branch, a tag, or a full 40-character commit SHA)");
        }
        if (text.contains("repository not found") || text.contains("could not read username")
                || text.contains("authentication") || text.contains("terminal prompts disabled")
                || text.contains("returned error: 4")) {
            return DeploymentStepException.permanent(
                    "Repository not found or not public. DeployHub builds public repositories only.");
        }
        return DeploymentStepException.transientError("Could not fetch the repository: " + lastLine(result));
    }

    /** {@code deployhub/p<project>:<commit12>-<settings8>}: same commit + same settings = same image. */
    static String imageRef(DeploymentSpec spec, String commit) {
        String settingsHash = sha256(String.join("\u0000", DockerfileGenerator.TEMPLATE_VERSION,
                spec.appType().name(), String.valueOf(spec.buildCommand()), String.valueOf(spec.startCommand()),
                String.valueOf(spec.appPort())));
        return "deployhub/p" + spec.projectId() + ":" + commit.substring(0, 12) + "-" + settingsHash.substring(0, 8);
    }

    // ---------------------------------------------------------------- start

    @Override
    public RunResult start(DeploymentSpec spec, ExecutionContext context) {
        if (spec.imageRef() == null) {
            throw DeploymentStepException.permanent("Nothing was built for this deployment");
        }
        String name = containerName(spec.deploymentId());
        stop(name); // left over from an earlier attempt of this step
        ensureNetwork(context);
        int first = settings.portRangeStart();
        int span = settings.portRangeEnd() - first + 1;
        int offset = ThreadLocalRandom.current().nextInt(span);
        for (int i = 0; i < Math.min(span, MAX_PORT_TRIES); i++) {
            int hostPort = first + (offset + i) % span;
            CommandRunner.Result run = docker(runCommand(spec, name, hostPort), QUICK, context, line -> { },
                    spec.environment());
            if (run.ok()) {
                context.info("Started container " + name + ": port " + spec.appPort() + " -> 127.0.0.1:" + hostPort
                        + " (memory " + settings.containerMemory() + ", cpus " + settings.containerCpus()
                        + ", processes " + settings.containerPidsLimit() + ")");
                try {
                    waitUntilHealthy(spec, name, hostPort, context);
                    // From now on Docker restarts the app if it crashes, or after Docker Desktop restarts.
                    CommandRunner.Result restart = docker(List.of("update", "--restart", "unless-stopped", name),
                            QUICK, context, line -> { });
                    if (!restart.ok()) {
                        context.warn("Could not enable automatic restarts: " + lastLine(restart));
                    }
                } catch (RuntimeException e) {
                    if (!(e instanceof StepCancelledException)) {
                        copyContainerLogs(name, context);
                    }
                    stop(name);
                    throw e;
                }
                return new RunResult(name, hostPort);
            }
            stop(name);
            String text = run.tailText().toLowerCase(Locale.ROOT);
            if (!text.contains("port is already allocated") && !text.contains("address already in use")) {
                throw dockerFailure("Starting the container", run, QUICK);
            }
        }
        throw DeploymentStepException.transientError("No free port found between " + first + " and "
                + settings.portRangeEnd());
    }

    private List<String> runCommand(DeploymentSpec spec, String name, int hostPort) {
        boolean ownDockerfile = spec.appType() == ApplicationType.DOCKERFILE;
        // No automatic restart until the health check has passed: a crash during startup then shows as
        // "exited" with its real exit code. Restarts are switched on once the app is healthy (see start()).
        List<String> command = new ArrayList<>(List.of("run", "--detach", "--name", name,
                "--restart", "no",
                "--memory", settings.containerMemory(), "--memory-swap", settings.containerMemory(),
                "--cpus", settings.containerCpus(), "--pids-limit", String.valueOf(settings.containerPidsLimit()),
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges"));
        if (ownDockerfile) {
            // Images like nginx start as root and then switch user; allow only what that needs.
            command.addAll(List.of("--cap-add", "CHOWN", "--cap-add", "SETUID", "--cap-add", "SETGID",
                    "--cap-add", "NET_BIND_SERVICE"));
        } else {
            command.addAll(List.of("--user", DockerfileGenerator.APP_USER));
        }
        // Only the names: docker takes each value from its own environment (see docker(..., env)).
        spec.environment().keySet().stream().sorted().forEach(variable -> command.addAll(List.of("-e", variable)));
        command.addAll(List.of("--network", NETWORK, "-e", "PORT=" + spec.appPort(),
                "--publish", "127.0.0.1:" + hostPort + ":" + spec.appPort(),
                "--label", "deployhub.project=" + spec.projectId(),
                "--label", "deployhub.deployment=" + spec.deploymentId()));
        boolean overrideStart = ownDockerfile && spec.startCommand() != null && !spec.startCommand().isBlank();
        if (overrideStart) {
            command.addAll(List.of("--entrypoint", "sh"));
        }
        command.add(spec.imageRef());
        if (overrideStart) {
            command.addAll(List.of("-c", spec.startCommand().trim()));
        }
        return command;
    }

    private void waitUntilHealthy(DeploymentSpec spec, String name, int hostPort, ExecutionContext context) {
        String path = spec.healthCheckPath() == null || spec.healthCheckPath().isBlank() ? "/" : spec.healthCheckPath();
        long timeoutNanos = settings.startTimeout().toNanos();
        long started = System.nanoTime();
        context.info("Waiting for the app to answer GET " + path + " (up to " + settings.startTimeout().toSeconds()
                + " s)");
        int lastStatus = -1;
        while (true) {
            context.throwIfCancelled();
            lastStatus = probe.status(hostPort, path);
            if (lastStatus > 0 && lastStatus < 500) {
                context.info("Health check passed: HTTP " + lastStatus + " on " + path + " after "
                        + Duration.ofNanos(System.nanoTime() - started).toSeconds() + " s");
                return;
            }
            // Started with "--restart no", so a crash shows as "exited" with its exit code. A restart count
            // above 0 is checked as well, in case a restart policy is ever applied earlier.
            List<String> state = new ArrayList<>();
            docker(List.of("inspect", "--format", "{{.State.Status}} {{.RestartCount}} {{.State.ExitCode}}", name), QUICK,
                    context, state::add);
            String[] parts = state.isEmpty() ? new String[0] : state.getLast().trim().split(" ");
            if (parts.length == 3 && (!parts[0].equals("running") && !parts[0].equals("created")
                    || !parts[1].equals("0"))) {
                throw DeploymentStepException.permanent("The app stopped (exit code " + parts[2]
                        + ") before answering its health check. See the app output above.");
            }
            if (System.nanoTime() - started > timeoutNanos) {
                throw DeploymentStepException.permanent(lastStatus >= 500
                        ? "Health check failed: GET " + path + " kept answering HTTP " + lastStatus
                        : "The app did not answer on port " + spec.appPort() + " within "
                        + settings.startTimeout().toSeconds() + " s. It must listen on 0.0.0.0 and the port in $PORT ("
                        + spec.appPort() + ").");
            }
            sleep(PROBE_INTERVAL, context);
        }
    }

    private void copyContainerLogs(String name, ExecutionContext context) {
        context.warn("Last output of the app:");
        docker(List.of("logs", "--tail", "40", name), QUICK, null, line -> context.output("    " + line));
    }

    // ---------------------------------------------------------------- stop / cleanup

    @Override
    public void stop(String containerName) {
        if (containerName == null) {
            return;
        }
        try {
            CommandRunner.Result result = docker(List.of("rm", "--force", containerName), QUICK, null, line -> { });
            if (!result.ok() && !result.tailText().toLowerCase(Locale.ROOT).contains("no such container")) {
                log.warn("Could not remove container {}: {}", containerName, lastLine(result));
            }
        } catch (RuntimeException e) {
            log.warn("Could not remove container {}: {}", containerName, e.getMessage());
        }
    }

    @Override
    public void pruneImages(Long projectId, Set<String> keep) {
        try {
            List<String> images = new ArrayList<>();
            docker(List.of("image", "ls", "--format", "{{.Repository}}:{{.Tag}}", "deployhub/p" + projectId), QUICK,
                    null, images::add);
            images.stream().map(String::trim).filter(ref -> !ref.isEmpty() && !keep.contains(ref))
                    .forEach(ref -> docker(List.of("image", "rm", ref), QUICK, null, line -> { }));
        } catch (RuntimeException e) {
            log.warn("Could not prune images of project {}: {}", projectId, e.getMessage());
        }
    }

    static String containerName(Long deploymentId) {
        return "deployhub-d" + deploymentId;
    }

    static String edgeName(Long projectId) {
        return "deployhub-edge-p" + projectId;
    }

    static String tunnelName(Long projectId) {
        return "deployhub-tunnel-p" + projectId;
    }

    // ---------------------------------------------------------------- public links

    private static final java.util.regex.Pattern QUICK_TUNNEL_URL =
            java.util.regex.Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    /**
     * A Cloudflare quick tunnel (no account) from the internet to the project's address proxy. It only connects
     * outwards, so nothing on this machine is opened to the network; only this one project becomes reachable.
     * Its link is random and changes when the tunnel restarts.
     */
    @Override
    public String startPublicLink(Long projectId) {
        String running = currentPublicLink(projectId);
        if (running != null) {
            return running; // adopt a running tunnel: the link people already have keeps working
        }
        String name = tunnelName(projectId);
        stop(name);
        ensureNetwork(null);
        requireOk(docker(List.of("run", "--detach", "--name", name, "--network", NETWORK,
                "--restart", "unless-stopped", "--memory", "128m", "--memory-swap", "128m", "--cpus", "0.5",
                "--pids-limit", "64", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--label", "deployhub.tunnel=" + projectId, tunnelImage,
                "tunnel", "--no-autoupdate", "--url", "http://" + edgeName(projectId) + ":" + EdgeConfig.LISTEN_PORT),
                Duration.ofMinutes(5), null, line -> { }), "Starting the public link");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            String link = currentPublicLink(projectId);
            if (link != null) {
                return link;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("The public link did not come up within 60 s. Is this Mac online?");
    }

    /** The newest link in the running tunnel's log (a restarted tunnel logs a new one after the old one). */
    @Override
    public String currentPublicLink(Long projectId) {
        String name = tunnelName(projectId);
        List<String> state = new ArrayList<>();
        if (!docker(List.of("container", "inspect", "--format", "{{.State.Running}}", name), QUICK, null, state::add)
                .ok() || state.isEmpty() || !state.getLast().trim().equals("true")) {
            return null;
        }
        List<String> log = new ArrayList<>();
        docker(List.of("logs", name), QUICK, null, log::add);
        String link = null;
        for (String line : log) {
            java.util.regex.Matcher matcher = QUICK_TUNNEL_URL.matcher(line);
            while (matcher.find()) {
                link = matcher.group();
            }
        }
        return link;
    }

    @Override
    public void stopPublicLink(Long projectId) {
        stop(tunnelName(projectId));
    }

    // ---------------------------------------------------------------- fixed project addresses

    @Override
    public void publish(Gateway gateway) {
        String name = edgeName(gateway.projectId());
        Path conf = null;
        try {
            conf = Files.createTempFile("deployhub-edge-p" + gateway.projectId(), ".conf");
            Files.writeString(conf, EdgeConfig.generate(gateway), StandardCharsets.UTF_8);
            // docker cp keeps the file mode. Temp files are owner-only (600), which the proxy can't read: it runs
            // without the capability to bypass file permissions. The file holds no secrets, only container names.
            Files.setPosixFilePermissions(conf, PosixFilePermissions.fromString("rw-r--r--"));
            ensureNetwork(null);
            List<String> state = new ArrayList<>();
            boolean exists = docker(List.of("container", "inspect", "--format", "{{.State.Running}}", name), QUICK,
                    null, state::add).ok();
            if (!exists) {
                // Created stopped, configured, then started: the stock config listens on port 80, which the
                // proxy isn't allowed to use (no NET_BIND_SERVICE), so it must never start with it.
                CommandRunner.Result create = docker(List.of("create", "--name", name, "--network", NETWORK,
                        "--restart", "unless-stopped", "--memory", "64m", "--memory-swap", "64m", "--cpus", "0.25",
                        "--pids-limit", "64", "--cap-drop", "ALL", "--cap-add", "CHOWN", "--cap-add", "SETUID",
                        "--cap-add", "SETGID", "--security-opt", "no-new-privileges",
                        "--publish", "127.0.0.1:" + gateway.publicPort() + ":" + EdgeConfig.LISTEN_PORT,
                        "--label", "deployhub.edge=" + gateway.projectId(), proxyImage),
                        Duration.ofMinutes(5), null, line -> { });
                requireOk(create, "Creating the address proxy");
            }
            requireOk(docker(List.of("cp", conf.toString(), name + ":/etc/nginx/conf.d/default.conf"), QUICK, null,
                    line -> { }), "Configuring the address proxy");
            boolean running = exists && !state.isEmpty() && state.getLast().trim().equals("true");
            requireOk(docker(running ? List.of("exec", name, "nginx", "-s", "reload") : List.of("start", name),
                    QUICK, null, line -> { }), running ? "Reloading the address proxy" : "Starting the address proxy");
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the address proxy configuration: " + e.getMessage(), e);
        } finally {
            if (conf != null) {
                deleteQuietly(conf);
            }
        }
    }

    @Override
    public void unpublish(Long projectId) {
        stop(edgeName(projectId));
    }

    /** One private network for apps and address proxies, so proxies reach apps by container name. */
    private void ensureNetwork(ExecutionContext context) {
        if (docker(List.of("network", "inspect", NETWORK), QUICK, context, line -> { }).ok()) {
            return;
        }
        CommandRunner.Result create = docker(List.of("network", "create", "--label", "deployhub=true", NETWORK),
                QUICK, context, line -> { });
        if (!create.ok() && !create.tailText().contains("already exists")) {
            throw dockerFailure("Creating the deployhub network", create, QUICK);
        }
    }

    private static void requireOk(CommandRunner.Result result, String what) {
        if (!result.ok()) {
            throw new IllegalStateException(what + " failed: " + lastLine(result));
        }
    }

    // ---------------------------------------------------------------- helpers

    private CommandRunner.Result docker(List<String> args, Duration timeout, ExecutionContext context,
                                        Consumer<String> onLine) {
        return docker(args, timeout, context, onLine, Map.of());
    }

    private CommandRunner.Result docker(List<String> args, Duration timeout, ExecutionContext context,
                                        Consumer<String> onLine, Map<String, String> env) {
        List<String> command = new ArrayList<>();
        command.add(settings.dockerCommand());
        command.addAll(args);
        try {
            return runner.run(command, env, null, timeout, context, onLine);
        } catch (CommandRunner.StartException e) {
            throw DeploymentStepException.transientError(
                    "The docker command was not found. Install Docker Desktop and make sure 'docker' is on the PATH.");
        }
    }

    private static DeploymentStepException dockerFailure(String what, CommandRunner.Result result, Duration timeout) {
        String text = result.tailText().toLowerCase(Locale.ROOT);
        if (text.contains("cannot connect to the docker daemon") || text.contains("is the docker daemon running")
                || text.contains("error during connect")) {
            return DeploymentStepException.transientError("Docker is not running. Start Docker Desktop; DeployHub "
                    + "will retry.");
        }
        if (result.timedOut()) {
            return DeploymentStepException.permanent(what + " took longer than " + timeout.toMinutes() + " min");
        }
        if (text.contains("failed to resolve source metadata") || text.contains("failed to do request")
                || text.contains("i/o timeout") || text.contains("tls handshake timeout")
                || text.contains("temporary failure in name resolution") || text.contains("connection reset")
                || text.contains("toomanyrequests") || text.contains("no such host")) {
            return DeploymentStepException.transientError(what + " could not download something (network): "
                    + lastLine(result));
        }
        return DeploymentStepException.permanent(what + " failed (exit code " + result.exitCode() + "): "
                + reason(result));
    }

    /** "#10 1.276 " in front of every line of docker build --progress=plain output. */
    private static final java.util.regex.Pattern BUILD_PREFIX = java.util.regex.Pattern.compile("^#\\d+ [\\d.]+ ");
    private static final java.util.regex.Pattern ERROR_LINE =
            java.util.regex.Pattern.compile("(?i)(\\berror\\b|\\berr!|\\bfailed\\b|exception|cannot find|not found)");
    /** Docker's own summary lines: they say that a step failed, not why. */
    private static final List<String> DOCKER_SUMMARY = List.of("view build details", "error: failed to build",
            "failed to solve", "did not complete successfully", "error: process \"", ">>>");

    /**
     * The most useful line for the failure message: the first real error in the output (e.g.
     * "npm error Missing: ... from lock file"), skipping Docker's summary lines and its Docker Desktop link;
     * otherwise the last line that isn't Docker noise.
     */
    static String reason(CommandRunner.Result result) {
        String fallback = null;
        for (String raw : result.tail()) {
            String line = BUILD_PREFIX.matcher(raw.trim()).replaceFirst("").trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.isEmpty() || DOCKER_SUMMARY.stream().anyMatch(lower::contains)) {
                continue;
            }
            if (ERROR_LINE.matcher(line).find()) {
                return line.length() > MAX_REASON ? line.substring(0, MAX_REASON) + "..." : line;
            }
            fallback = line;
        }
        if (fallback == null) {
            return lastLine(result);
        }
        return fallback.length() > MAX_REASON ? fallback.substring(0, MAX_REASON) + "..." : fallback;
    }

    private static String lastLine(CommandRunner.Result result) {
        for (int i = result.tail().size() - 1; i >= 0; i--) {
            String line = result.tail().get(i).trim();
            if (!line.isEmpty()) {
                return line.length() > MAX_REASON ? line.substring(0, MAX_REASON) + "..." : line;
            }
        }
        return "no output";
    }

    private static void sleep(Duration duration, ExecutionContext context) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker interrupted", e);
        }
        context.throwIfCancelled();
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort; the folder is under the temp directory
                }
            });
        } catch (IOException | RuntimeException e) {
            log.debug("Could not delete {}: {}", path, e.getMessage());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

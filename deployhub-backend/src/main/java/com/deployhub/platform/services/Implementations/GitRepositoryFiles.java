package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.exceptions.ServiceUnavailableException;
import com.deployhub.platform.execution.GitSafety;
import com.deployhub.platform.services.Interface.IRepositoryFiles;
import com.deployhub.platform.utils.RepositoryUrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads a repository with git, using the same hardened settings as deploys (GitSafety): a shallow fetch of only the
 * branch's file list (--filter=blob:none), then single files on demand. Works for GitHub, GitLab and Bitbucket alike,
 * needs no API token and has no rate limit. The temporary folder is deleted when the snapshot is closed.
 */
@Slf4j
@Service
public class GitRepositoryFiles implements IRepositoryFiles {

    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_PATHS = 50_000;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern BRANCH = Pattern.compile("^(?!-)(?!.*\\.\\.)[A-Za-z0-9._/-]{1,100}$");
    private static final Pattern PATH = Pattern.compile("^(?!/)(?!.*\\.\\.)[A-Za-z0-9._/ -]{1,300}$");

    private final String git;
    private final Path workspace;
    private final boolean allowLocalRepositories;

    @Autowired
    public GitRepositoryFiles(DeployHubProperties properties) {
        this(properties.execution().gitCommand(), Path.of(properties.execution().workspace()), false);
    }

    /** @param allowLocalRepositories tests only: also accept file:// repositories */
    GitRepositoryFiles(String git, Path workspace, boolean allowLocalRepositories) {
        this.git = git;
        this.workspace = workspace;
        this.allowLocalRepositories = allowLocalRepositories;
    }

    @Override
    public Snapshot open(String repositoryUrl, String branch, boolean withSmallFiles) {
        boolean local = allowLocalRepositories && repositoryUrl.startsWith("file://");
        if (!local && !RepositoryUrlValidator.isAllowed(repositoryUrl)) {
            throw new RepositoryNotReadable("That repository URL isn't allowed.");
        }
        if (branch == null || !BRANCH.matcher(branch).matches()) {
            throw new RepositoryNotReadable("That isn't a valid branch name.");
        }
        Path dir;
        try {
            Files.createDirectories(workspace);
            dir = Files.createTempDirectory(workspace, "detect-");
        } catch (IOException e) {
            throw new ServiceUnavailableException("Could not prepare a folder to read the repository.", e);
        }
        try {
            run(dir.getParent(), FETCH_TIMEOUT, "init", "-q", dir.toString());
            run(dir, FETCH_TIMEOUT, "remote", "add", "origin", repositoryUrl.trim());
            Result fetch = run(dir, FETCH_TIMEOUT, "fetch", "-q", "--depth", "1",
                    withSmallFiles ? "--filter=blob:limit=256k" : "--filter=blob:none", "--no-tags",
                    "origin", "--end-of-options", branch);
            if (!fetch.ok()) {
                String text = fetch.text().toLowerCase(Locale.ROOT);
                if (fetch.timedOut()) {
                    throw new ServiceUnavailableException("Reading the repository took too long. Try again.");
                }
                if (text.contains("couldn't find remote ref") || text.contains("could not find remote branch")) {
                    throw new RepositoryNotReadable("The branch '" + branch + "' doesn't exist in this repository.");
                }
                if (text.contains("could not resolve host") || text.contains("unable to access")
                        && !text.contains("returned error: 4")) {
                    throw new ServiceUnavailableException("Couldn't reach the Git host to read the repository.");
                }
                throw new RepositoryNotReadable("The repository wasn't found or isn't public. DeployHub can only "
                        + "read public repositories.");
            }
            Result list = run(dir, READ_TIMEOUT, "ls-tree", "-r", "--name-only", "-z", "FETCH_HEAD");
            if (!list.ok()) {
                throw new ServiceUnavailableException("Couldn't list the repository's files.");
            }
            Set<String> paths = new LinkedHashSet<>();
            for (String path : list.text().split("\0")) {
                if (!path.isBlank() && paths.size() < MAX_PATHS) {
                    paths.add(path);
                }
            }
            return new GitSnapshot(dir, Set.copyOf(paths));
        } catch (RuntimeException e) {
            delete(dir);
            throw e;
        }
    }

    private final class GitSnapshot implements Snapshot {
        private final Path dir;
        private final Set<String> paths;

        GitSnapshot(Path dir, Set<String> paths) {
            this.dir = dir;
            this.paths = paths;
        }

        @Override
        public Set<String> paths() {
            return paths;
        }

        @Override
        public Optional<String> read(String path) {
            if (!paths.contains(path) || !PATH.matcher(path).matches()) {
                return Optional.empty();
            }
            Result file = run(dir, READ_TIMEOUT, "cat-file", "blob", "FETCH_HEAD:" + path);
            return file.ok() && !file.truncated() ? Optional.of(file.text()) : Optional.empty();
        }

        @Override
        public void close() {
            delete(dir);
        }
    }

    private record Result(int exitCode, boolean timedOut, boolean truncated, String text) {
        boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    private Result run(Path workingDir, Duration timeout, String... args) {
        List<String> command = new ArrayList<>();
        command.add(git);
        command.addAll(GitSafety.flags(allowLocalRepositories));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDir.toFile()).redirectErrorStream(true);
        for (Map.Entry<String, String> e : GitSafety.environment().entrySet()) {
            if (e.getValue() == null) {
                builder.environment().remove(e.getKey());
            } else {
                builder.environment().put(e.getKey(), e.getValue());
            }
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ServiceUnavailableException("git is not installed or not on the PATH.", e);
        }
        try (InputStream out = process.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    out.transferTo(new LimitedStream(buffer, MAX_FILE_BYTES + 1));
                } catch (IOException ignored) {
                    // process ended or was killed
                }
            });
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            reader.join(Duration.ofSeconds(2));
            boolean truncated = buffer.size() > MAX_FILE_BYTES;
            return new Result(finished ? process.exitValue() : -1, !finished, truncated,
                    buffer.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while reading the repository.", e);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new ServiceUnavailableException("Could not read git's output.", e);
        }
    }

    /** Keeps at most limit bytes and silently drops the rest (so a huge file can't fill the memory). */
    private static final class LimitedStream extends java.io.OutputStream {
        private final ByteArrayOutputStream target;
        private final int limit;

        LimitedStream(ByteArrayOutputStream target, int limit) {
            this.target = target;
            this.limit = limit;
        }

        @Override
        public void write(int b) {
            if (target.size() < limit) {
                target.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            int room = limit - target.size();
            if (room > 0) {
                target.write(b, off, Math.min(room, len));
            }
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete {}: {}", dir, e.getMessage());
        }
    }
}

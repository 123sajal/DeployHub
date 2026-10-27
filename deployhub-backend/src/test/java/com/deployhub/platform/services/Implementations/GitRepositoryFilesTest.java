package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.services.Interface.IRepositoryFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real git against a local repository (file://, allowed only through the test constructor). */
class GitRepositoryFilesTest {

    @TempDir Path temp;

    private Path origin;
    private Path workspace;
    private GitRepositoryFiles files;

    @BeforeEach
    void createRepository() throws Exception {
        origin = temp.resolve("origin");
        workspace = temp.resolve("work");
        git(temp, "init", "-q", "-b", "main", origin.toString());
        Files.createDirectories(origin.resolve("backend"));
        Files.writeString(origin.resolve("backend/package.json"), "{\"scripts\":{\"start\":\"node server.js\"}}");
        Files.writeString(origin.resolve("README.md"), "hello");
        Files.write(origin.resolve("big.bin"), new byte[600 * 1024]);
        git(origin, "add", ".");
        git(origin, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "first");
        files = new GitRepositoryFiles("git", workspace, true);
    }

    @Test
    void listsTheFilesAndReadsOneOnDemandThenCleansUp() throws IOException {
        try (IRepositoryFiles.Snapshot snapshot = files.open("file://" + origin, "main")) {
            assertThat(snapshot.paths()).contains("backend/package.json", "README.md", "big.bin");
            assertThat(snapshot.read("backend/package.json")).hasValueSatisfying(text -> assertThat(text).contains("node server.js"));
            assertThat(snapshot.read("nope.txt")).isEmpty();
            assertThat(snapshot.read("big.bin")).as("over 512 KB").isEmpty();
            assertThat(snapshot.read("../outside")).isEmpty();
        }
        try (Stream<Path> left = Files.list(workspace)) {
            assertThat(left).as("temporary folder deleted").isEmpty();
        }
    }

    @Test
    void aMissingBranchOrRepositoryIsExplained() {
        assertThatThrownBy(() -> files.open("file://" + origin, "does-not-exist"))
                .isInstanceOf(IRepositoryFiles.RepositoryNotReadable.class)
                .hasMessageContaining("branch 'does-not-exist' doesn't exist");
        assertThatThrownBy(() -> files.open("file://" + temp.resolve("nothing"), "main"))
                .isInstanceOf(IRepositoryFiles.RepositoryNotReadable.class)
                .hasMessageContaining("wasn't found or isn't public");
        assertThatThrownBy(() -> new GitRepositoryFiles("git", workspace, false).open("file://" + origin, "main"))
                .as("file:// only in tests").isInstanceOf(IRepositoryFiles.RepositoryNotReadable.class);
        assertThatThrownBy(() -> files.open("file://" + origin, "--upload-pack=touch /tmp/x"))
                .isInstanceOf(IRepositoryFiles.RepositoryNotReadable.class);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + ": " + out);
        }
    }
}

package com.deployhub.platform.execution;

import com.deployhub.platform.deployment.DeploymentLogWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Runs real processes through /bin/sh. */
class ProcessCommandRunnerTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stop() {
        runner.shutdown();
        later.shutdownNow();
    }

    private static List<String> sh(String script) {
        return List.of("/bin/sh", "-c", script);
    }

    @Test
    void streamsOutputLinesAndReportsTheExitCode() {
        List<String> lines = new ArrayList<>();
        CommandRunner.Result result = runner.run(sh("echo one; echo two >&2; exit 3"), Map.of(), null,
                Duration.ofSeconds(10), null, lines::add);

        assertThat(lines).containsExactly("one", "two");
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.ok()).isFalse();
        assertThat(result.tail()).containsExactly("one", "two");
    }

    @Test
    void killsAProgramThatRunsTooLong() {
        long started = System.nanoTime();
        CommandRunner.Result result = runner.run(sh("echo started; sleep 30"), Map.of(), null,
                Duration.ofMillis(300), null, line -> { });

        assertThat(result.timedOut()).isTrue();
        assertThat(result.ok()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void cancellingKillsTheProgramAndStopsTheStep() {
        ExecutionContext context = new ExecutionContext(1L, mock(DeploymentLogWriter.class), 100);
        later.schedule(() -> context.cancel(ExecutionContext.CancelReason.CANCELLED_BY_USER), 300, TimeUnit.MILLISECONDS);
        long started = System.nanoTime();

        assertThatThrownBy(() -> runner.run(sh("sleep 30"), Map.of(), null, Duration.ofMinutes(1), context, line -> { }))
                .isInstanceOf(StepCancelledException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void environmentChangesCanAlsoRemoveVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("DEPLOYHUB_TEST_VAR", "set");
        env.put("HOME", null);
        List<String> lines = new ArrayList<>();
        runner.run(sh("echo \"$DEPLOYHUB_TEST_VAR|${HOME-unset}\""), env, null, Duration.ofSeconds(10), null, lines::add);

        assertThat(lines).containsExactly("set|unset");
    }

    @Test
    void aMissingProgramIsReportedAsStartException() {
        assertThatThrownBy(() -> runner.run(List.of("definitely-not-a-real-program-xyz"), Map.of(), null,
                Duration.ofSeconds(5), null, line -> { }))
                .isInstanceOf(CommandRunner.StartException.class);
    }
}

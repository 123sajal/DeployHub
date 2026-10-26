package com.deployhub.platform.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs an external program (git, docker) without a shell: arguments are passed as a list, so nothing
 * from a project's settings is ever interpreted by a shell on this machine.
 */
public interface CommandRunner {

    /**
     * @param exitCode  the program's exit code (-1 when it timed out)
     * @param timedOut  true when it was killed for exceeding the timeout
     * @param tail      the last lines of output, for error messages
     */
    record Result(int exitCode, boolean timedOut, List<String> tail) {

        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        public String tailText() {
            return String.join("\n", tail);
        }
    }

    /** The program could not be started at all (e.g. it isn't installed or not on the PATH). */
    class StartException extends RuntimeException {
        public StartException(String program, Throwable cause) {
            super("Could not run '" + program + "': " + cause.getMessage(), cause);
        }
    }

    /**
     * Runs {@code command} and passes every output line (stdout and stderr together) to {@code onLine}.
     *
     * @param env      environment changes on top of this process's environment; a null value removes the variable
     * @param context  when cancelled, the program is killed and {@link StepCancelledException} is thrown;
     *                 may be null
     */
    Result run(List<String> command, Map<String, String> env, Path workingDir, Duration timeout,
               ExecutionContext context, Consumer<String> onLine);
}

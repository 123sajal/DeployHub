package com.deployhub.platform.execution;

import com.deployhub.platform.deployment.DeploymentLogWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ExecutionContextTest {

    @Test
    void environmentValuesArePrintedAsStars() {
        DeploymentLogWriter logs = mock(DeploymentLogWriter.class);
        ExecutionContext context = new ExecutionContext(7L, logs, 100);
        context.maskSecrets(List.of("hunter2-db-pass", "abc"));

        context.output("Connecting with password hunter2-db-pass ...");
        context.warn("failed for hunter2-db-pass");
        context.info("short values like abc stay readable");

        verify(logs).info(7L, "Connecting with password *** ...");
        verify(logs).warn(7L, "failed for ***");
        verify(logs).info(7L, "short values like abc stay readable");
        verify(logs, never()).info(eq(7L), org.mockito.ArgumentMatchers.contains("hunter2"));
        verify(logs, never()).warn(eq(7L), org.mockito.ArgumentMatchers.contains("hunter2"));
        verify(logs, never()).error(eq(7L), anyString());
    }
}

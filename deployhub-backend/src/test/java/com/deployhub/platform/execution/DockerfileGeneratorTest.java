package com.deployhub.platform.execution;

import com.deployhub.platform.models.enums.ApplicationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerfileGeneratorTest {

    @Test
    void nodeProjectGetsBuildStepPortAndExecFormStart() {
        String file = DockerfileGenerator.generate(ApplicationType.NODE_JS, "npm ci && npm run build", "npm start", 3000);

        assertThat(file.lines()).containsSubsequence(
                "FROM node:22-alpine",
                "WORKDIR /app",
                "RUN chown 10001:10001 /app",
                "COPY --chown=10001:10001 . .",
                "ENV PORT=3000 SERVER_PORT=3000",
                "RUN npm ci && npm run build",
                "ENV PORT=3000 SERVER_PORT=3000 HOME=/tmp",
                "EXPOSE 3000",
                "CMD [\"sh\",\"-c\",\"npm start\"]");
        assertThat(file).doesNotContain("NODE_ENV"); // would make npm ci skip the dev dependencies builds need
    }

    @Test
    void startCommandIsJsonEncodedSoQuotesCannotBreakTheDockerfile() {
        String file = DockerfileGenerator.generate(ApplicationType.PYTHON, null,
                "gunicorn -b \"0.0.0.0:$PORT\" app:app", 8000);

        assertThat(file).contains("CMD [\"sh\",\"-c\",\"gunicorn -b \\\"0.0.0.0:$PORT\\\" app:app\"]");
        assertThat(file.lines().filter(line -> line.startsWith("RUN "))).as("no build step without a build command")
                .containsExactly("RUN chown 10001:10001 /app");
        assertThat(file).contains("FROM python:3.12-slim").contains("PYTHONUNBUFFERED=1");
    }

    @Test
    void eachTypeHasItsBaseImage() {
        assertThat(DockerfileGenerator.baseImage(ApplicationType.JAVA_SPRING_BOOT)).isEqualTo("eclipse-temurin:21-jdk");
        assertThat(DockerfileGenerator.baseImage(ApplicationType.STATIC_SITE)).isEqualTo("node:22-alpine");
        assertThatThrownBy(() -> DockerfileGenerator.baseImage(ApplicationType.DOCKERFILE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAppFolderMakesTheCommandsRunThereAfterTheWholeRepositoryIsCopied() {
        String file = DockerfileGenerator.generate(ApplicationType.NODE_JS, "npm ci", "npm start", 3000, "apps/api");

        assertThat(file).containsSubsequence("WORKDIR /app\n", "COPY --chown=10001:10001 . .\n",
                "WORKDIR /app/apps/api\n", "RUN npm ci\n", "CMD ");
        assertThat(DockerfileGenerator.generate(ApplicationType.NODE_JS, "npm ci", "npm start", 3000))
                .doesNotContain("WORKDIR /app/");
    }
}

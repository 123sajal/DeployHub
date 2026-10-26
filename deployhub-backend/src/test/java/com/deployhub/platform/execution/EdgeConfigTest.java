package com.deployhub.platform.execution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeConfigTest {

    @Test
    void routesGoToOtherProjectsAndEverythingElseToTheProjectItself() {
        String conf = EdgeConfig.generate(new DeploymentExecutor.Gateway(5L, "ems-frontend", 19001, "deployhub-d40", 3000,
                List.of(new DeploymentExecutor.Route("/api", "deployhub-d38", 5000, "ems-backend"))));

        assertThat(conf).contains("listen 8080;").contains("resolver 127.0.0.11");
        assertThat(conf).containsSubsequence(
                "location = /api {", "set $upstream http://deployhub-d38:5000;", "proxy_pass $upstream;",
                "location ^~ /api/ {", "set $upstream http://deployhub-d38:5000;",
                "location / {", "set $upstream http://deployhub-d40:3000;");
    }

    @Test
    void aTargetWithoutALiveAppAnswers503WithAReason() {
        String conf = EdgeConfig.generate(new DeploymentExecutor.Gateway(5L, "ems-frontend", 19001, null, null,
                List.of(new DeploymentExecutor.Route("/api/", null, null, "ems-backend"))));

        assertThat(conf).contains("return 503 \"No live deployment of project ems-backend for /api\\n\";")
                .contains("return 503 \"No live deployment of project ems-frontend\\n\";");
    }

    @Test
    void longerPrefixesComeFirstAndUnsafeValuesAreRejected() {
        String conf = EdgeConfig.generate(new DeploymentExecutor.Gateway(5L, "web", 19001, "deployhub-d1", 80, List.of(
                new DeploymentExecutor.Route("/api", "deployhub-d2", 80, "a"),
                new DeploymentExecutor.Route("/api/admin", "deployhub-d3", 80, "b"))));
        assertThat(conf.indexOf("/api/admin/")).isLessThan(conf.indexOf("location ^~ /api/ "));

        assertThatThrownBy(() -> EdgeConfig.normalisePrefix("/api; return 200")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EdgeConfig.normalisePrefix("/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EdgeConfig.generate(new DeploymentExecutor.Gateway(5L, "web", 19001,
                "evil;host", 80, List.of()))).isInstanceOf(IllegalArgumentException.class);
    }
}

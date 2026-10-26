package com.deployhub.platform.controllers;

import com.deployhub.platform.deployment.DeploymentMetrics;
import com.deployhub.platform.support.IntegrationTestSupport;
import com.deployhub.platform.worker.DeploymentJobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Liveness/readiness probes, the Prometheus endpoint and the dashboard metrics API. */
@AutoConfigureMetrics // tests switch metrics export off by default; this suite checks the Prometheus output
class ObservabilityIntegrationTest extends IntegrationTestSupport {

    @Autowired DeploymentJobWorker worker;
    @Autowired DeploymentMetrics metrics;

    @Test
    void livenessAndReadinessArePublicAndRevealNoDetails() throws Exception {
        anonymousMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        anonymousMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void prometheusIsReadableOnlyFromTheMonitoringNetwork() throws Exception {
        anonymousMvc.perform(get("/actuator/prometheus")) // MockMvc requests come from 127.0.0.1
                .andExpect(status().isOk());
        anonymousMvc.perform(get("/actuator/prometheus").with(request -> {
                    request.setRemoteAddr("203.0.113.9");
                    return request;
                }))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void pipelineMetricsAppearInPrometheus() throws Exception {
        createDeployment(createProject("api", "npm ci"));
        createDeployment(createProject("flaky-app", "npm run flaky"));
        worker.runAvailableJobs(10);
        createDeployment(createProject("waiting", "npm ci"));
        metrics.refreshQueueDepth();

        String scrape = anonymousMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape)
                .contains("deployhub_queue_jobs{state=\"waiting\"} 1.0")
                .contains("deployhub_queue_jobs{state=\"running\"} 0.0")
                .contains("deployhub_queue_wait_seconds_count{attempt=\"first\"}")
                .contains("deployhub_queue_wait_seconds_count{attempt=\"retry\"}")
                .contains("deployhub_jobs_finished_total{outcome=\"succeeded\"}")
                .contains("deployhub_jobs_retries_total")
                .contains("deployhub_deployments_duration_seconds_count{status=\"live\"}");
    }

    @Test
    void dashboardMetricsCoverTheLastHourOfYourOwnDeployments() throws Exception {
        createDeployment(createProject("api", "npm ci"));
        createDeployment(createProject("broken", "fail"));
        worker.runAvailableJobs(10);
        createDeployment(createProject("waiting", "npm ci"));

        mockMvc.perform(get("/api/dashboard/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowMinutes").value(60))
                .andExpect(jsonPath("$.platformQueue.waiting").value(1))
                .andExpect(jsonPath("$.platformQueue.retryScheduled").value(0))
                .andExpect(jsonPath("$.platformQueue.running").value(0))
                .andExpect(jsonPath("$.finished").value(2))
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.cancelled").value(0))
                .andExpect(jsonPath("$.successRate").value(closeTo(0.5, 0.0001)))
                .andExpect(jsonPath("$.avgQueueWaitMs").value(notNullValue()))
                .andExpect(jsonPath("$.maxQueueWaitMs").value(notNullValue()));
    }

    @Test
    void dashboardMetricsAreEmptyForANewUserButStillShowTheSharedQueue() throws Exception {
        createDeployment(createProject("api", "npm ci")); // Alice's job, waiting in the shared queue
        TestUser bob = registerUser("Bob", "bob@example.com");

        mvcAs(bob).perform(get("/api/dashboard/metrics"))
                .andExpect(jsonPath("$.platformQueue.waiting").value(1))
                .andExpect(jsonPath("$.finished").value(0))
                .andExpect(jsonPath("$.avgQueueWaitMs").doesNotExist())
                .andExpect(jsonPath("$.successRate").doesNotExist());
    }
}

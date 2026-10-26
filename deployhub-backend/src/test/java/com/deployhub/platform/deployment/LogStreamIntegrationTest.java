package com.deployhub.platform.deployment;

import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.DeploymentLog;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.services.Implementations.LogStreamService;
import com.deployhub.platform.support.IntegrationTestSupport;
import com.deployhub.platform.worker.DeploymentJobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/deployments/{id}/logs/stream: Server-Sent Events, read here the way a browser's EventSource would. */
class LogStreamIntegrationTest extends IntegrationTestSupport {

    @Autowired DeploymentJobWorker worker;
    @Autowired LogStreamService streams;

    /** One parsed SSE event. */
    record Event(String id, String name, String data) {
    }

    private static List<Event> parse(String body) {
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String id = null, name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("id:")) id = line.substring(3);
                else if (line.startsWith("event:")) name = line.substring(6);
                else if (line.startsWith("data:")) data.append(line.substring(5));
            }
            if (name != null) events.add(new Event(id, name, data.toString()));
        }
        return events;
    }

    private MvcResult open(MockMvc mvc, long deploymentId, long after, String lastEventId) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/deployments/{id}/logs/stream", deploymentId)
                .param("after", String.valueOf(after))
                .accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
    }

    /** Waits until the server closes the stream (it does so after the "end" event). */
    private static List<Event> readToEnd(MvcResult stream) throws Exception {
        stream.getAsyncResult(10_000);
        return parse(stream.getResponse().getContentAsString());
    }

    private List<Long> logIdsInDatabase(long deploymentId) {
        return deploymentLogRepository.findAll().stream()
                .filter(l -> l.getDeploymentId() == deploymentId)
                .map(DeploymentLog::getId).sorted().toList();
    }

    private static List<Long> logIds(List<Event> events) {
        return events.stream().filter(e -> e.name().equals("log")).map(e -> Long.parseLong(e.id())).toList();
    }

    @Test
    void finishedDeploymentStreamsEveryLineThenEnds() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        worker.runAvailableJobs(10);

        MvcResult stream = open(mockMvc, id, 0, null);
        assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        List<Event> events = readToEnd(stream);

        assertThat(events.getFirst().name()).isEqualTo("status");
        assertThat(events.getFirst().data()).contains("\"status\":\"LIVE\"");
        assertThat(logIds(events)).isEqualTo(logIdsInDatabase(id));
        assertThat(events.stream().filter(e -> e.name().equals("log")).map(Event::data))
                .allMatch(d -> d.contains("\"level\"") && d.contains("\"message\""));
        assertThat(events.getLast().name()).isEqualTo("end");
        assertThat(events.getLast().data()).contains("\"status\":\"LIVE\"");
    }

    @Test
    void resumesAfterTheCursorOrTheLastEventIdWhicheverIsFurther() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        worker.runAvailableJobs(10);
        List<Long> all = logIdsInDatabase(id);
        long third = all.get(2);
        long fifth = all.get(4);

        assertThat(logIds(readToEnd(open(mockMvc, id, third, null)))).isEqualTo(all.subList(3, all.size()));
        // A browser reconnecting by itself sends Last-Event-ID; the original URL still has the old cursor.
        assertThat(logIds(readToEnd(open(mockMvc, id, third, String.valueOf(fifth)))))
                .isEqualTo(all.subList(5, all.size()));
        assertThat(logIds(readToEnd(open(mockMvc, id, 0, "not-a-number")))).isEqualTo(all);
    }

    @Test
    void followsARunningDeploymentLineByLineUntilItFinishes() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        MvcResult stream = open(mockMvc, id, 0, null);

        worker.runAvailableJobs(10); // the deployment runs while the stream is open

        List<Event> events = readToEnd(stream);
        assertThat(logIds(events)).as("no gaps, no duplicates").isEqualTo(logIdsInDatabase(id));
        List<String> statuses = events.stream().filter(e -> e.name().equals("status")).map(Event::data).toList();
        assertThat(statuses.getFirst()).contains("QUEUED");
        assertThat(statuses.getLast()).contains("LIVE");
        assertThat(events.getLast().name()).isEqualTo("end");
    }

    @Test
    void cancellingEndsTheStream() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        MvcResult stream = open(mockMvc, id, 0, null);

        mockMvc.perform(post("/api/deployments/{id}/cancel", id)).andExpect(status().isOk());

        List<Event> events = readToEnd(stream);
        assertThat(events.getLast().data()).contains("CANCELLED");
        assertThat(events).anyMatch(e -> e.name().equals("log") && e.data().contains("cancelled by user"));
    }

    @Test
    void streamsAreLimitedPerUser() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        int before = streams.openStreams();
        List<MvcResult> open = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            open.add(open(mockMvc, id, 0, null));
        }

        mockMvc.perform(get("/api/deployments/{id}/logs/stream", id).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isTooManyRequests());

        // Finishing the deployment closes all five; the slots are free again.
        mockMvc.perform(post("/api/deployments/{id}/cancel", id)).andExpect(status().isOk());
        for (MvcResult stream : open) {
            readToEnd(stream);
        }
        assertThat(streams.openStreams()).isEqualTo(before);
        readToEnd(open(mockMvc, id, 0, null));
    }

    @Test
    void streamingWorksAgainAfterTheApplicationIsStoppedAndStarted() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        worker.runAvailableJobs(10);

        streams.stop();  // what a context restart (or Spring's test-context pausing) does
        streams.start();

        assertThat(readToEnd(open(mockMvc, id, 0, null)).getLast().name()).isEqualTo("end");
    }

    @Test
    void otherUsersDeploymentsAndAnonymousCallersAreRejected() throws Exception {
        Deployment theirs = deploymentRepository.save(
                Deployment.queue(otherUsersProject(), null, ExecutionMode.SIMULATED, Instant.now()));

        mockMvc.perform(get("/api/deployments/{id}/logs/stream", theirs.getId()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());
        anonymousMvc.perform(get("/api/deployments/{id}/logs/stream", theirs.getId()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }
}

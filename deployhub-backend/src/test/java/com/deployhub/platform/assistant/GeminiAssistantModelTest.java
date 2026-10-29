package com.deployhub.platform.assistant;

import com.deployhub.platform.config.AssistantProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Gemini client against a fake Google server on this machine: what is sent, and how answers are read. */
class GeminiAssistantModelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> keyHeader = new AtomicReference<>();
    private final AtomicReference<JsonNode> body = new AtomicReference<>();
    private volatile int status = 200;
    /** Statuses to answer first (e.g. 503, 503), before falling back to status. */
    private final java.util.Deque<Integer> firstStatuses = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String answer = "{}";
    private GeminiAssistantModel model;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString());
            keyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            body.set(JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            calls.incrementAndGet();
            Integer first = firstStatuses.poll();
            int code = first == null ? status : first;
            byte[] bytes = (code == 200 ? answer
                    : "{\"error\":{\"code\":" + code + ",\"message\":\"This model is currently experiencing high demand.\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        model = new GeminiAssistantModel(properties("secret-test-key"), new long[]{1, 1});
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AssistantProperties properties(String key) {
        return new AssistantProperties(true, AssistantProperties.Provider.GEMINI,
                new AssistantProperties.Claude("", "claude-haiku-5-5", "claude-opus-5-5"),
                new AssistantProperties.Gemini(key, "gemini-3.5-flash-lite", "gemini-3.8-flash",
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), true, false),
                20, 88, 50, 20, 300, Duration.ofSeconds(5));
    }

    private static AssistantModel.Request chat(String question) {
        return new AssistantModel.Request("gemini-3.5-flash-lite", "You are the DeployHub assistant.",
                List.of(new AssistantModel.Turn(AssistantModel.Role.USER, "earlier question"),
                        new AssistantModel.Turn(AssistantModel.Role.ASSISTANT, "earlier answer"),
                        new AssistantModel.Turn(AssistantModel.Role.USER, question)), 1500, false);
    }

    @Test
    void sendsTheConversationWithTheKeyInAHeaderAndReadsTheAnswer() {
        answer = """
                {"candidates":[{"content":{"role":"model","parts":[
                   {"text":"thinking about it","thought":true},{"text":"Click "},{"text":"Deploy."}]},
                 "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":30,"thoughtsTokenCount":50}}""";

        AssistantModel.Reply reply = model.ask(chat("how do I deploy?"));

        assertThat(reply.text()).isEqualTo("Click Deploy.");
        assertThat(reply.refused()).isFalse();
        assertThat(reply.cutOff()).isFalse();
        assertThat(reply.usage()).isEqualTo(new AssistantModel.Usage(120, 80));
        assertThat(path.get()).isEqualTo("/v1beta/models/gemini-3.5-flash-lite:generateContent")
                .doesNotContain("secret-test-key");
        assertThat(keyHeader.get()).isEqualTo("secret-test-key");
        JsonNode sent = body.get();
        assertThat(sent.path("systemInstruction").path("parts").path(0).path("text").asString())
                .isEqualTo("You are the DeployHub assistant.");
        assertThat(sent.path("contents").valueStream().map(c -> c.path("role").asString()).toList())
                .containsExactly("user", "model", "user");
        assertThat(sent.path("contents").path(2).path("parts").path(0).path("text").asString()).isEqualTo("how do I deploy?");
        assertThat(sent.path("generationConfig").path("maxOutputTokens").asLong()).isGreaterThanOrEqualTo(8192);
    }

    @Test
    void safetyBlocksAreRefusalsAndTheTokenLimitIsACutOff() {
        answer = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"},\"usageMetadata\":{\"promptTokenCount\":10}}";
        assertThat(model.ask(chat("x")).refused()).isTrue();

        answer = "{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"SAFETY\"}]}";
        assertThat(model.ask(chat("x")).refused()).isTrue();

        answer = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Half an ans\"}]},\"finishReason\":\"MAX_TOKENS\"}]}";
        AssistantModel.Reply cut = model.ask(chat("x"));
        assertThat(cut.cutOff()).isTrue();
        assertThat(cut.text()).isEqualTo("Half an ans");
    }

    @Test
    void errorsSuchAsAnUsedUpFreeQuotaMakeTheAssistantFallBack() {
        status = 429;
        answer = "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted (e.g. check quota).\"}}";
        assertThatThrownBy(() -> model.ask(chat("x"))).isInstanceOf(AssistantUnavailableException.class);

        status = 200;
        answer = "not json";
        assertThatThrownBy(() -> model.ask(chat("x"))).isInstanceOf(AssistantUnavailableException.class);

        server.stop(0);
        assertThatThrownBy(() -> model.ask(chat("x"))).isInstanceOf(AssistantUnavailableException.class);
    }

    @Test
    void aBusyModelIsTriedAgainTwiceThenGivenUp() {
        answer = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Answer after retry\"}]},\"finishReason\":\"STOP\"}]}";
        firstStatuses.addAll(List.of(503, 503));
        assertThat(model.ask(chat("x")).text()).isEqualTo("Answer after retry");
        assertThat(calls.get()).isEqualTo(3);

        calls.set(0);
        firstStatuses.addAll(List.of(503, 500, 503));
        assertThatThrownBy(() -> model.ask(chat("x"))).isInstanceOf(AssistantUnavailableException.class);
        assertThat(calls.get()).isEqualTo(3);

        calls.set(0);
        firstStatuses.add(429); // a used-up quota isn't retried: it won't be back in a second
        assertThatThrownBy(() -> model.ask(chat("x"))).isInstanceOf(AssistantUnavailableException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void withoutAKeyOrWithAStrangeModelNameNothingIsSent() {
        assertThatThrownBy(() -> new GeminiAssistantModel(properties(""), new long[]{1, 1}).ask(chat("x")))
                .isInstanceOf(AssistantUnavailableException.class);
        assertThatThrownBy(() -> model.ask(new AssistantModel.Request("../../other", "s",
                List.of(new AssistantModel.Turn(AssistantModel.Role.USER, "q")), 100, false)))
                .isInstanceOf(AssistantUnavailableException.class);
        assertThat(body.get()).isNull();
    }

    @Test
    void readsTheStudentIdCardAsJsonWithTheImageInline() {
        answer = """
                {"candidates":[{"content":{"parts":[{"text":"```json\\n{\\"readable\\":true,\\"studentIdCard\\":true,\
                \\"nameOnCard\\":\\"SAJAL SINGH TOMAR\\",\\"institution\\":\\"Delhi University\\",\
                \\"validUntil\\":\\"2027-06-30\\",\\"problem\\":\\"\\"}\\n```"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":900,"candidatesTokenCount":60}}""";

        AssistantModel.IdCardResult result = model.readStudentId("gemini-3.5-flash-lite", new byte[]{1, 2, 3});

        assertThat(result.refused()).isFalse();
        assertThat(result.reading()).isEqualTo(new AssistantModel.IdCardReading(true, true, "SAJAL SINGH TOMAR",
                "Delhi University", "2027-06-30", ""));
        JsonNode part = body.get().path("contents").path(0).path("parts").path(0);
        assertThat(part.path("inline_data").path("mime_type").asString()).isEqualTo("image/jpeg");
        assertThat(part.path("inline_data").path("data").asString()).isEqualTo("AQID");
        assertThat(body.get().path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");

        answer = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I can't read this\"}]},\"finishReason\":\"STOP\"}]}";
        assertThatThrownBy(() -> model.readStudentId("gemini-3.5-flash-lite", new byte[]{1}))
                .isInstanceOf(AssistantUnavailableException.class);
    }
}

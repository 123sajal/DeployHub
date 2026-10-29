package com.deployhub.platform.assistant;

import com.deployhub.platform.assistant.AssistantModel.Reply;
import com.deployhub.platform.assistant.AssistantModel.Usage;
import com.deployhub.platform.models.entities.AssistantUsage;
import com.deployhub.platform.models.entities.DeploymentLog;
import com.deployhub.platform.models.enums.LogLevel;
import com.deployhub.platform.repositories.DeploymentExplanationRepository;
import com.deployhub.platform.support.AssistantTestSupport;
import com.deployhub.platform.worker.DeploymentJobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The AI assistant with a fake AI: sign-up checks and codes, chat, explanations, limits, masking and fallbacks. */
class AssistantIntegrationTest extends AssistantTestSupport {

    @Autowired DeploymentJobWorker worker;
    @Autowired DeploymentExplanationRepository explanationRepository;

    private ResultActions postJson(MockMvc mvc, String path, String json) throws Exception {
        return mvc.perform(post(path).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String chatJson(String question) {
        return """
                {"page":"signup","messages":[
                  {"role":"ASSISTANT","text":"Hi! I'm the DeployHub assistant."},
                  {"role":"USER","text":"%s"}]}""".formatted(question);
    }

    // ---- sign-up ----

    @Test
    void theStatusSaysTheAiIsAvailableAndNeedsNoLogin() throws Exception {
        anonymousMvc.perform(get("/api/assistant/status")).andExpect(jsonPath("$.aiAvailable").value(true));
    }

    @Test
    void signUpAnswersAreCheckedWithFriendlySuggestions() throws Exception {
        postJson(anonymousMvc, "/api/assistant/signup/check", "{\"field\":\"NAME\",\"value\":\"  Sajal Tomar \"}")
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.value").value("Sajal Tomar"))
                .andExpect(jsonPath("$.message").value(startsWith("Nice to meet you, Sajal!")));
        postJson(anonymousMvc, "/api/assistant/signup/check", "{\"field\":\"NAME\",\"value\":\"me@x.com\"}")
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("looks like an email")));
        for (String bad : new String[]{"Sajal123", "Sajal_T", "Sajal!", "R2D2", "-"}) {
            postJson(anonymousMvc, "/api/assistant/signup/check", "{\"field\":\"NAME\",\"value\":\"%s\"}".formatted(bad))
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.message").value(containsString("only letters and spaces")));
        }
        postJson(anonymousMvc, "/api/assistant/signup/check", "{\"field\":\"NAME\",\"value\":\"सजल   तोमर\"}")
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.value").value("सजल तोमर"));
        postJson(anonymousMvc, "/api/assistant/signup/check", "{\"field\":\"EMAIL\",\"value\":\" Sajal@GMIAL.com\"}")
                .andExpect(jsonPath("$.ok").value(true)) // a suggestion: the user may keep their own
                .andExpect(jsonPath("$.message").value("Did you mean sajal@gmail.com?"))
                .andExpect(jsonPath("$.suggestion").value("sajal@gmail.com"));
        postJson(anonymousMvc, "/api/assistant/signup/check", "{\"field\":\"EMAIL\",\"value\":\"sajal@\"}")
                .andExpect(jsonPath("$.ok").value(false));
        postJson(anonymousMvc, "/api/assistant/signup/check", "{\"field\":\"EMAIL\",\"value\":\"sajal@gmail.com\"}")
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.suggestion").doesNotExist());
        assertThat(ai.requests).isEmpty(); // checks need no AI, so they work without a key and cost nothing
    }

    @Test
    void anEmailCodeProvesTheEmailAndSignUpRecordsItWithTheOccupation() throws Exception {
        postJson(anonymousMvc, "/api/assistant/signup/email-code", "{\"email\":\" New@Example.com \"}")
                .andExpect(status().isAccepted());
        String code = emailedCode("new@example.com", 1);
        assertThat(emailCodeRepository.findAll()).singleElement()
                .satisfies(c -> assertThat(c.getCodeHash()).startsWith("{bcrypt}").doesNotContain(code));

        String proof = com.jayway.jsonpath.JsonPath.read(postJson(anonymousMvc, "/api/assistant/signup/email-code/verify",
                        "{\"email\":\"new@example.com\",\"code\":\"%s\"}".formatted(code))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.proof");

        postJson(anonymousMvc, "/api/auth/register", """
                {"displayName":"New","email":"new@example.com","password":"%s","occupation":"STUDENT","emailProof":"%s"}
                """.formatted(PASSWORD, proof))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occupation").value("STUDENT"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.studentVerifiedUntil").doesNotExist());
        // The code works once.
        postJson(anonymousMvc, "/api/assistant/signup/email-code/verify",
                "{\"email\":\"new@example.com\",\"code\":\"%s\"}".formatted(code))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aProofForAnotherEmailOrAMadeUpOneIsRefused() throws Exception {
        postJson(anonymousMvc, "/api/assistant/signup/email-code", "{\"email\":\"one@example.com\"}");
        String code = emailedCode("one@example.com", 1);
        String proof = com.jayway.jsonpath.JsonPath.read(postJson(anonymousMvc,
                "/api/assistant/signup/email-code/verify", "{\"email\":\"one@example.com\",\"code\":\"%s\"}"
                        .formatted(code)).andReturn().getResponse().getContentAsString(), "$.proof");

        for (String badProof : new String[]{proof, "9999999999.forged", "nonsense"}) {
            postJson(anonymousMvc, "/api/auth/register", """
                    {"displayName":"Two","email":"two@example.com","password":"%s","emailProof":"%s"}
                    """.formatted(PASSWORD, badProof))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.emailProof").value(containsString("expired")));
        }
        assertThat(userRepository.findByEmail("two@example.com")).isEmpty();
    }

    @Test
    void theSimpleSignUpFormStillWorksWithoutAProof() throws Exception {
        assertThat(userRepository.findByEmail("alice@example.com")).hasValueSatisfying(u -> {
            assertThat(u.getEmailVerifiedAt()).isNull();
            assertThat(u.getOccupation()).isNull();
        });
        mockMvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void wrongCodesAreRefusedAndAnExistingAccountIsToldToLogIn() throws Exception {
        postJson(anonymousMvc, "/api/assistant/signup/email-code", "{\"email\":\"alice@example.com\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already has a DeployHub account")));

        postJson(anonymousMvc, "/api/assistant/signup/email-code", "{\"email\":\"new@example.com\"}");
        String code = emailedCode("new@example.com", 1);
        String wrong = code.equals("000000") ? "111111" : "000000";
        postJson(anonymousMvc, "/api/assistant/signup/email-code/verify",
                "{\"email\":\"new@example.com\",\"code\":\"%s\"}".formatted(wrong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").value(containsString("wrong or has expired")));
        postJson(anonymousMvc, "/api/assistant/signup/email-code/verify",
                "{\"email\":\"other@example.com\",\"code\":\"%s\"}".formatted(code))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendingCodesIsLimitedPerEmail() throws Exception {
        for (int i = 0; i < 5; i++) {
            postJson(anonymousMvc, "/api/assistant/signup/email-code", "{\"email\":\"flood@example.com\"}")
                    .andExpect(status().isAccepted());
        }
        postJson(anonymousMvc, "/api/assistant/signup/email-code", "{\"email\":\"flood@example.com\"}")
                .andExpect(status().isTooManyRequests());
    }

    // ---- chat ----

    @Test
    void chatBeforeSigningInUsesTheChatModelWithDeployHubFactsAndThePage() throws Exception {
        ai.answer = request -> new Reply("You can deploy for free.", new Usage(2000, 100), false, false);

        postJson(anonymousMvc, "/api/assistant/chat", chatJson("Is it free?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("You can deploy for free."))
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.notice").doesNotExist());

        AssistantModel.Request sent = ai.requests.getFirst();
        assertThat(sent.model()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(sent.thorough()).isFalse();
        assertThat(sent.system()).contains("DeployHub facts").contains("Never ask for passwords")
                .contains("data to").contains("Reply in the language the user writes in");
        // The greeting is dropped (the API needs the user to speak first); the page is attached to the question.
        assertThat(sent.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.role()).isEqualTo(AssistantModel.Role.USER);
            assertThat(turn.text()).contains("<page>signup, not signed in</page>").endsWith("Is it free?");
        });
        assertThat(usageRepository.findAll()).singleElement().satisfies(u -> {
            assertThat(u.getSubject()).isEqualTo("ip:127.0.0.1");
            assertThat(u.getRequests()).isEqualTo(1);
            assertThat(u.getInputTokens()).isEqualTo(2000);
            assertThat(u.getCostMillipaise()).isPositive();
        });
    }

    @Test
    void secretsPastedIntoTheChatNeverReachTheAi() throws Exception {
        mockMvc.perform(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(
                        chatJson("my key " + "rzp_" + "test_AbCdEf123456 and DB_PASSWORD=hunter22 fail")))
                .andExpect(status().isOk());
        String sent = ai.requests.getFirst().turns().getLast().text();
        assertThat(sent).doesNotContain("AbCdEf123456").doesNotContain("hunter22").contains("signed in</page>");
        assertThat(usageRepository.findAll()).singleElement()
                .satisfies(u -> assertThat(u.getSubject()).isEqualTo("user:" + alice.id()));
    }

    @Test
    void afterTheDailyLimitTheChatGivesBuiltInAnswersForFree() throws Exception {
        for (int i = 0; i < 3; i++) {
            postJson(anonymousMvc, "/api/assistant/chat", chatJson("hello")).andExpect(jsonPath("$.source").value("AI"));
        }
        postJson(anonymousMvc, "/api/assistant/chat", chatJson("How much does a deploy cost?"))
                .andExpect(jsonPath("$.source").value("BUILT_IN"))
                .andExpect(jsonPath("$.notice").value(containsString("today's AI questions")))
                .andExpect(jsonPath("$.text").value(containsString("Payment page")));
        assertThat(ai.requests).hasSize(3);
        // A signed-in user has their own allowance.
        mockMvc.perform(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(chatJson("hi")))
                .andExpect(jsonPath("$.source").value("AI"));
    }

    @Test
    void whenTodaysBudgetIsSpentEveryoneGetsBuiltInAnswersUntilMidnight() throws Exception {
        AssistantUsage spent = new AssistantUsage();
        spent.setUsageDay(java.time.LocalDate.now(AssistantBudget.INDIA).toString());
        spent.setSubject("user:999999");
        spent.setRequests(9);
        spent.setCostMillipaise(20 * 100_000L); // Rs 20, the default daily budget
        usageRepository.save(spent);

        anonymousMvc.perform(get("/api/assistant/status")).andExpect(jsonPath("$.aiAvailable").value(false));
        mockMvc.perform(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(chatJson("how do I add a database?")))
                .andExpect(jsonPath("$.source").value("BUILT_IN"))
                .andExpect(jsonPath("$.notice").value(containsString("today's limit")))
                .andExpect(jsonPath("$.text").value(containsString("Add MySQL database")));
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void anOutageARefusalOrAnEmptyAnswerFallsBackToTheBuiltInAnswer() throws Exception {
        ai.down = true;
        mockMvc.perform(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(chatJson("help")))
                .andExpect(jsonPath("$.source").value("BUILT_IN"))
                .andExpect(jsonPath("$.notice").value(containsString("not reachable")));
        ai.down = false;
        ai.answer = request -> new Reply("", new Usage(100, 5), true, false);
        mockMvc.perform(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(chatJson("help")))
                .andExpect(jsonPath("$.source").value("BUILT_IN"));
        ai.answer = request -> new Reply("  ", new Usage(100, 5), false, false);
        mockMvc.perform(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(chatJson("help")))
                .andExpect(jsonPath("$.source").value("BUILT_IN"));
        // Refused and empty answers were still paid for, so they count.
        assertThat(usageRepository.findAll()).singleElement().satisfies(u -> assertThat(u.getRequests()).isEqualTo(2));
    }

    @Test
    void theConversationMustEndWithTheUserAndStayShort() throws Exception {
        postJson(anonymousMvc, "/api/assistant/chat", """
                {"page":"x","messages":[{"role":"USER","text":"hi"},{"role":"ASSISTANT","text":"hello"}]}""")
                .andExpect(status().isBadRequest());
        StringBuilder many = new StringBuilder("{\"messages\":[");
        for (int i = 0; i < 13; i++) {
            many.append(i == 0 ? "" : ",").append("{\"role\":\"USER\",\"text\":\"q\"}");
        }
        postJson(anonymousMvc, "/api/assistant/chat", many.append("]}").toString()).andExpect(status().isBadRequest());
        assertThat(ai.requests).isEmpty();
    }

    // ---- explaining failures ----

    /** A deployment that failed (the simulator fails "npm run fail" builds), with a secret in its log. */
    private long failedDeploymentWithSecretInLog(String projectName) throws Exception {
        long projectId = createProject(projectName, "npm run fail");
        mockMvc.perform(put("/api/projects/{id}/env/{name}", projectId, "STRIPE_KEY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"stripe-value-123456\"}"))
                .andExpect(status().is2xxSuccessful());
        long deploymentId = createDeployment(projectId);
        worker.runAvailableJobs(10);
        deploymentLogRepository.save(new DeploymentLog(deploymentId, LogLevel.ERROR,
                "Error: bad key stripe-value-123456 for Stripe", Instant.now()));
        mockMvc.perform(get("/api/deployments/{id}", deploymentId)).andExpect(jsonPath("$.status").value("FAILED"));
        return deploymentId;
    }

    @Test
    void aFailedDeploymentIsExplainedOnceWithTheExplainModelAndSecretsMasked() throws Exception {
        long deploymentId = failedDeploymentWithSecretInLog("broken-app");
        ai.answer = request -> new Reply("What went wrong: the build script is missing.", new Usage(9000, 800), false, false);
        String body = "{\"topic\":\"DEPLOYMENT_FAILED\",\"deploymentId\":%d}".formatted(deploymentId);

        mockMvc.perform(post("/api/assistant/explain").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("What went wrong: the build script is missing."))
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.saved").value(false));

        AssistantModel.Request sent = ai.requests.getFirst();
        assertThat(sent.model()).isEqualTo("gemini-3.8-flash");
        assertThat(sent.thorough()).isTrue();
        String question = sent.turns().getFirst().text();
        assertThat(question).contains("<project>name: broken-app").contains("Build command: npm run fail")
                .contains("<log>").contains("bad key ******").doesNotContain("stripe-value-123456");
        assertThat(sent.system()).contains("What went wrong:").contains("How to fix it:");

        // Asking again shows the saved explanation without paying again.
        mockMvc.perform(post("/api/assistant/explain").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.text").value("What went wrong: the build script is missing."));
        assertThat(ai.requests).hasSize(1);
        assertThat(explanationRepository.findById(deploymentId)).hasValueSatisfying(
                e -> assertThat(e.getModel()).isEqualTo("gemini-3.8-flash"));
    }

    @Test
    void whenTheExplainModelIsBusyTheChatModelExplainsInstead() throws Exception {
        long deploymentId = failedDeploymentWithSecretInLog("busy-app");
        ai.answer = request -> {
            if (request.model().equals("gemini-3.8-flash")) {
                throw new AssistantUnavailableException("This model is currently experiencing high demand");
            }
            return new Reply("What went wrong: the start command crashed.", new Usage(5000, 300), false, false);
        };
        mockMvc.perform(post("/api/assistant/explain").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"DEPLOYMENT_FAILED\",\"deploymentId\":%d}".formatted(deploymentId)))
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.text").value("What went wrong: the start command crashed."));
        assertThat(ai.requests).extracting(AssistantModel.Request::model)
                .containsExactly("gemini-3.8-flash", "gemini-3.5-flash-lite");
        assertThat(ai.requests.get(1).turns()).isEqualTo(ai.requests.get(0).turns()); // same log, same question
    }

    @Test
    void whenTheAiIsDownTheFailureReasonAndAChecklistAreShownAndNothingIsSaved() throws Exception {
        long deploymentId = failedDeploymentWithSecretInLog("down-app");
        ai.down = true;
        mockMvc.perform(post("/api/assistant/explain").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"DEPLOYMENT_FAILED\",\"deploymentId\":%d}".formatted(deploymentId)))
                .andExpect(jsonPath("$.source").value("BUILT_IN"))
                .andExpect(jsonPath("$.text").value(containsString("Things to check")));
        assertThat(explanationRepository.findById(deploymentId)).isEmpty();
    }

    @Test
    void onlyYourOwnFailedDeploymentsCanBeExplained() throws Exception {
        long projectId = createProject("fine-app", "npm ci");
        long okDeployment = createDeployment(projectId);
        worker.runAvailableJobs(10);
        mockMvc.perform(post("/api/assistant/explain").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"DEPLOYMENT_FAILED\",\"deploymentId\":%d}".formatted(okDeployment)))
                .andExpect(status().isConflict());

        long failed = failedDeploymentWithSecretInLog("mine");
        var bob = registerUser("Bob", "bob@example.com");
        mvcAs(bob).perform(post("/api/assistant/explain").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"DEPLOYMENT_FAILED\",\"deploymentId\":%d}".formatted(failed)))
                .andExpect(status().isNotFound());
        anonymousMvc.perform(post("/api/assistant/explain").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"PAYMENT_FAILED\",\"errorText\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void otherErrorsAreExplainedQuicklyWithTheProjectsSettings() throws Exception {
        long projectId = createProject("needs-keys", "npm ci");
        mockMvc.perform(post("/api/assistant/explain").contentType(MediaType.APPLICATION_JSON).content("""
                        {"topic":"MISSING_SETTINGS","projectId":%d,"errorText":"Missing: JWT_SECRET, SMTP_PASS=abc12345"}
                        """.formatted(projectId)))
                .andExpect(jsonPath("$.source").value("AI"));
        AssistantModel.Request sent = ai.requests.getFirst();
        assertThat(sent.model()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(sent.turns().getFirst().text()).contains("Topic: MISSING_SETTINGS").contains("name: needs-keys")
                .contains("<error>Missing: JWT_SECRET").doesNotContain("abc12345");

        mockMvc.perform(post("/api/assistant/explain").contentType(MediaType.APPLICATION_JSON).content("""
                        {"topic":"IMPORT_REFUSED","projectId":%d,"errorText":"x"}""".formatted(otherUsersProject().getId())))
                .andExpect(status().isNotFound());
    }
}

package com.deployhub.platform.support;

import com.deployhub.platform.repositories.AssistantUsageRepository;
import com.deployhub.platform.repositories.EmailCodeRepository;
import com.deployhub.platform.services.Interface.IMailService;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The assistant with a fake AI (a "key" is set, so DeployHub believes the AI is available) and captured emails.
 * Subclasses share one Spring context.
 */
@Import(AssistantTestSupport.FakeAiConfig.class)
@TestPropertySource(properties = {
        "deployhub.assistant.gemini.api-key=test-only-key",
        // A paid Gemini project: costs are counted and student ID photos may be checked.
        "deployhub.assistant.gemini.free-tier=false",
        "deployhub.assistant.max-requests-per-user-per-day=6",
        "deployhub.assistant.max-requests-per-ip-per-day=3"})
public abstract class AssistantTestSupport extends IntegrationTestSupport {

    @Autowired protected FakeAssistantModel ai;
    @Autowired protected AssistantUsageRepository usageRepository;
    @Autowired protected EmailCodeRepository emailCodeRepository;
    @MockitoBean protected IMailService mailService;

    @BeforeEach
    void resetAssistant() {
        ai.reset();
        usageRepository.deleteAllInBatch();
        emailCodeRepository.deleteAllInBatch();
    }

    /** The code emailed to this address (the n-th code sent to it in this test). */
    protected String emailedCode(String email, int nth) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(5000).times(nth)).sendEmailCode(eq(email), anyString(), code.capture(), any());
        return code.getValue();
    }

    @TestConfiguration
    public static class FakeAiConfig {

        @Bean
        @Primary
        FakeAssistantModel fakeAssistantModel() {
            return new FakeAssistantModel();
        }
    }
}

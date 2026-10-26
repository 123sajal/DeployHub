package com.deployhub.platform.auth;

import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "deployhub.security.max-registrations-per-ip=3")
class RegistrationThrottleTest extends IntegrationTestSupport {

    @Test
    void tooManySignUpsFromOneAddressAreRefused() throws Exception {
        registerUser("Two", "two@example.com");   // Alice was the first
        registerUser("Three", "three@example.com");

        anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Four","email":"four@example.com","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"));
    }
}

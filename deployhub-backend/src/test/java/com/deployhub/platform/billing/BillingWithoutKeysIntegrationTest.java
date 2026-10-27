package com.deployhub.platform.billing;

import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Billing on but no Razorpay keys: the free projects still work, paid deploys say payments aren't set up. */
@TestPropertySource(properties = "deployhub.billing.enabled=true")
class BillingWithoutKeysIntegrationTest extends IntegrationTestSupport {

    @Test
    void theFreeProjectsWorkAndPaidDeploysExplainThatPaymentsArentSetUp() throws Exception {
        createDeployment(createProject("first-app", "npm ci"));
        createDeployment(createProject("second-app", "npm ci"));

        mockMvc.perform(post("/api/projects/{id}/deployments", createProject("third-app", "npm ci")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.detail").value(containsString("payments aren't set up")));
        mockMvc.perform(post("/api/billing/orders"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("RAZORPAY_KEY_ID")));
        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.paymentsConfigured").value(false));
    }
}

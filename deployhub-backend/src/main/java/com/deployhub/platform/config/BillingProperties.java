package com.deployhub.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Paid deploys: a user's first freeProjects deployed projects are free, every deploy of another project costs
 * pricePerDeploy.
 *
 * @param enabled        false = every deploy is free (the tests run this way)
 * @param freeProjects   how many projects per user are free (redeploys included), e.g. 2 for a backend + frontend
 * @param pricePerDeploy in the currency's smallest unit (paise for INR: 4900 = ₹49)
 * @param currency       ISO code, e.g. INR
 * @param razorpay       API keys and address; paid deploys are refused until the keys are set
 */
@ConfigurationProperties(prefix = "deployhub.billing")
public record BillingProperties(boolean enabled, int freeProjects, long pricePerDeploy, String currency,
                                Razorpay razorpay) {

    /**
     * @param keyId     rzp_test_... (test mode, no real money) or rzp_live_...
     * @param keySecret signs API calls and verifies Checkout's payment signatures; never sent to the browser
     */
    public record Razorpay(String keyId, String keySecret, URI apiUrl, Duration timeout) {

        public boolean configured() {
            return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
        }
    }
}

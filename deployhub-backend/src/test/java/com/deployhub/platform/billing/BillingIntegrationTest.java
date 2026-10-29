package com.deployhub.platform.billing;

import com.deployhub.platform.exceptions.PaymentVerificationException;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.repositories.PaymentRepository;
import com.deployhub.platform.services.Interface.IPaymentGateway;
import com.deployhub.platform.support.IntegrationTestSupport;
import com.deployhub.platform.worker.DeploymentJobWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paid deploys with a fake Razorpay (no keys, no network): the first two deployed projects are free, others need a
 * credit (402 without one), a verified Checkout payment buys one, and a deploy that doesn't happen gives it back.
 */
@Import(BillingIntegrationTest.FakeRazorpayConfig.class) // explicit, so BillingOracleTest gets it too
@TestPropertySource(properties = {
        "deployhub.billing.enabled=true",
        "deployhub.billing.free-projects=2",
        "deployhub.billing.price-per-deploy=4900",
        "deployhub.billing.razorpay.key-id=rzp_test_fake",
        "deployhub.billing.razorpay.key-secret=" + BillingIntegrationTest.SECRET})
class BillingIntegrationTest extends IntegrationTestSupport {

    static final String SECRET = "test-only-razorpay-secret";

    @Autowired FakeRazorpay razorpay;
    @Autowired DeploymentJobWorker worker;
    @Autowired PaymentRepository paymentRepository;

    @BeforeEach
    void resetRazorpay() {
        razorpay.reset();
    }

    /** Deploys two projects, so the user's free projects are used up. */
    private void useUpFreeProjects() throws Exception {
        createDeployment(createProject("free-one", "npm ci"));
        createDeployment(createProject("free-two", "npm ci"));
    }

    @Test
    void theFirstTwoDeployedProjectsAreFreeIncludingTheirRedeploys() throws Exception {
        mockMvc.perform(get("/api/billing"))
                .andExpect(jsonPath("$.freeProjects").value(2))
                .andExpect(jsonPath("$.freeProjectsLeft").value(2))
                .andExpect(jsonPath("$.freeProjectAvailable").value(true));
        long backend = createProject("ems-backend", "npm ci");
        createDeployment(backend);
        createDeployment(backend);
        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.freeProjectsLeft").value(1));

        long frontend = createProject("ems-frontend", "npm ci");
        createDeployment(frontend);
        createDeployment(frontend);

        mockMvc.perform(get("/api/projects/{id}", backend)).andExpect(jsonPath("$.free").value(true));
        mockMvc.perform(get("/api/projects/{id}", frontend)).andExpect(jsonPath("$.free").value(true));
        mockMvc.perform(get("/api/billing"))
                .andExpect(jsonPath("$.freeProjectsLeft").value(0))
                .andExpect(jsonPath("$.freeProjectAvailable").value(false))
                .andExpect(jsonPath("$.credits").value(0));
        assertThat(auditEventRepository.findAll()).filteredOn(e -> e.getType() == AuditEventType.FREE_PROJECT_CLAIMED)
                .hasSize(2);
    }

    @Test
    void aThirdProjectNeedsPaymentAnd402SaysWhatItCosts() throws Exception {
        useUpFreeProjects();
        long second = createProject("second-app", "npm ci");

        mockMvc.perform(post("/api/projects/{id}/deployments", second))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value("Payment required"))
                .andExpect(jsonPath("$.detail").value(containsString("Your 2 free projects are used")))
                .andExpect(jsonPath("$.detail").value(containsString("₹49")))
                .andExpect(jsonPath("$.price").value(4900))
                .andExpect(jsonPath("$.currency").value("INR"));

        mockMvc.perform(get("/api/projects/{id}", second)).andExpect(jsonPath("$.free").value(false));
        assertThat(deploymentRepository.findAll()).noneMatch(d -> d.getProject().getId() == second);
    }

    @Test
    void aVerifiedPaymentBuysExactlyOneDeploy() throws Exception {
        useUpFreeProjects();
        long second = createProject("second-app", "npm ci");

        String orderId = buyCredit(mockMvc);

        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.credits").value(1));
        createDeployment(second);
        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.credits").value(0));
        mockMvc.perform(post("/api/projects/{id}/deployments", second)).andExpect(status().isPaymentRequired());

        assertThat(paymentRepository.findAll()).singleElement().satisfies(p -> {
            assertThat(p.getRazorpayOrderId()).isEqualTo(orderId);
            assertThat(p.getUsedAt()).isNotNull();
            assertThat(p.getDeploymentId()).isNotNull();
        });
        assertThat(auditEventRepository.findAll()).extracting(e -> e.getType())
                .contains(AuditEventType.PAYMENT_ORDER_CREATED, AuditEventType.PAYMENT_RECEIVED,
                        AuditEventType.DEPLOY_CREDIT_USED);
    }

    @Test
    void theOrderUsesTheConfiguredPriceAndOnlyThePublicKey() throws Exception {
        mockMvc.perform(post("/api/billing/orders"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("order_test1"))
                .andExpect(jsonPath("$.keyId").value("rzp_test_fake"))
                .andExpect(jsonPath("$.amount").value(4900))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.keySecret").doesNotExist());
        assertThat(razorpay.orderAmounts).containsExactly(4900L);
    }

    @Test
    void aVerifiedStudentPaysThePriceMinusTheStudentDiscount() throws Exception {
        userRepository.findById(alice.id()).ifPresent(user -> {
            user.setStudentVerifiedUntil(java.time.LocalDate.now().plusMonths(6));
            userRepository.save(user);
        });
        mockMvc.perform(get("/api/billing"))
                .andExpect(jsonPath("$.pricePerDeploy").value(4900))
                .andExpect(jsonPath("$.yourPrice").value(4400))
                .andExpect(jsonPath("$.verifiedStudent").value(true));
        mockMvc.perform(post("/api/billing/orders")).andExpect(jsonPath("$.amount").value(4400));
        assertThat(razorpay.orderAmounts).containsExactly(4400L);

        useUpFreeProjects();
        mockMvc.perform(post("/api/projects/{id}/deployments", createProject("paid-app", "npm ci")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.detail").value(containsString("₹44")))
                .andExpect(jsonPath("$.price").value(4400));
    }

    @Test
    void anExpiredStudentCheckPaysTheFullPrice() throws Exception {
        userRepository.findById(alice.id()).ifPresent(user -> {
            user.setStudentVerifiedUntil(java.time.LocalDate.now().minusDays(2));
            userRepository.save(user);
        });
        mockMvc.perform(post("/api/billing/orders")).andExpect(jsonPath("$.amount").value(4900));
    }

    @Test
    void aWrongSignatureCreditsNothing() throws Exception {
        String orderId = createOrder(mockMvc);
        razorpay.pay(orderId, "pay_good1", "captured", 4900);

        verify(mockMvc, orderId, "pay_good1", sign(orderId, "pay_other"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Payment not verified"));
        assertThat(paymentRepository.countUnusedCredits(alice.id())).isZero();
    }

    @Test
    void aSignedPaymentThatRazorpayDoesntShowAsPaidCreditsNothing() throws Exception {
        String orderId = createOrder(mockMvc);
        razorpay.pay(orderId, "pay_failed1", "failed", 4900);
        verify(mockMvc, orderId, "pay_failed1", sign(orderId, "pay_failed1")).andExpect(status().isBadRequest());

        razorpay.pay(orderId, "pay_cheap1", "captured", 100); // paid, but not the price
        verify(mockMvc, orderId, "pay_cheap1", sign(orderId, "pay_cheap1")).andExpect(status().isBadRequest());

        assertThat(paymentRepository.countUnusedCredits(alice.id())).isZero();
    }

    @Test
    void anAuthorizedPaymentIsCapturedThenCredited() throws Exception {
        String orderId = createOrder(mockMvc);
        razorpay.pay(orderId, "pay_auth1", "authorized", 4900);

        verify(mockMvc, orderId, "pay_auth1", sign(orderId, "pay_auth1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credits").value(1));
        assertThat(razorpay.captured).containsExactly("pay_auth1");
    }

    @Test
    void verifyingTwiceGivesOneCredit() throws Exception {
        String orderId = createOrder(mockMvc);
        razorpay.pay(orderId, "pay_twice1", "captured", 4900);

        verify(mockMvc, orderId, "pay_twice1", sign(orderId, "pay_twice1")).andExpect(jsonPath("$.credits").value(1));
        verify(mockMvc, orderId, "pay_twice1", sign(orderId, "pay_twice1")).andExpect(jsonPath("$.credits").value(1));
    }

    @Test
    void aPaymentWhoseVerifyWasMissedIsPickedUpLater() throws Exception {
        String orderId = createOrder(mockMvc);
        razorpay.pay(orderId, "pay_closed1", "captured", 4900); // paid, then the browser closed

        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.credits").value(1));
    }

    @Test
    void someoneElsesOrderCantBeVerified() throws Exception {
        String orderId = createOrder(mockMvc);
        razorpay.pay(orderId, "pay_alice1", "captured", 4900);
        MockMvc bob = mvcAs(registerUser("Bob", "bob@example.com"));

        verify(bob, orderId, "pay_alice1", sign(orderId, "pay_alice1")).andExpect(status().isNotFound());
        assertThat(paymentRepository.countUnusedCredits(alice.id())).isZero();
    }

    @Test
    void aCancelledOrFailedDeployGivesItsCreditBack() throws Exception {
        useUpFreeProjects();
        long second = createProject("second-app", "npm ci");
        buyCredit(mockMvc);

        long cancelled = createDeployment(second);
        mockMvc.perform(post("/api/deployments/{id}/cancel", cancelled)).andExpect(status().isOk());
        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.credits").value(1));

        long failed = createDeployment(createProject("broken-app", "fail-build")); // the simulator fails this one
        worker.runAvailableJobs(10);
        assertThat(deploymentRepository.findById(failed).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.FAILED);
        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.credits").value(1));
    }

    @Test
    void rollbacksAreFree() throws Exception {
        useUpFreeProjects();
        long second = createProject("second-app", "npm ci");
        buyCredit(mockMvc);
        buyCredit(mockMvc);
        long v1 = createDeployment(second);
        worker.runAvailableJobs(10);
        createDeployment(second);
        worker.runAvailableJobs(10);

        mockMvc.perform(post("/api/deployments/{id}/rollback", v1)).andExpect(status().isAccepted());
        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.credits").value(0));
    }

    @Test
    void deletingAFreeProjectDoesntUnlockAnotherOne() throws Exception {
        long first = createProject("free-one", "npm ci");
        createDeployment(first);
        createDeployment(createProject("free-two", "npm ci"));
        worker.runAvailableJobs(10);
        mockMvc.perform(delete("/api/projects/{id}", first)).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/projects/{id}/deployments", createProject("again-app", "npm ci")))
                .andExpect(status().isPaymentRequired());
        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.freeProjectsLeft").value(0));
    }

    private String createOrder(MockMvc mvc) throws Exception {
        String body = mvc.perform(post("/api/billing/orders")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var matcher = Pattern.compile("\"orderId\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** Pays for one deploy the way the browser does: order, Checkout (faked), verify. */
    private String buyCredit(MockMvc mvc) throws Exception {
        String orderId = createOrder(mvc);
        String paymentId = "pay_" + orderId.substring("order_".length());
        razorpay.pay(orderId, paymentId, "captured", 4900);
        verify(mvc, orderId, paymentId, sign(orderId, paymentId)).andExpect(status().isOk());
        return orderId;
    }

    private static ResultActions verify(MockMvc mvc, String orderId, String paymentId, String signature)
            throws Exception {
        return mvc.perform(post("/api/billing/orders/{id}/verify", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":\"%s\",\"signature\":\"%s\"}".formatted(paymentId, signature)));
    }

    /** What Razorpay Checkout returns: HMAC-SHA256("orderId|paymentId") with the key secret. */
    private static String sign(String orderId, String paymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
    }

    @TestConfiguration
    static class FakeRazorpayConfig {

        @Bean
        @Primary
        FakeRazorpay fakeRazorpay() {
            return new FakeRazorpay();
        }
    }

    /** Razorpay's side, in memory: orders get ids, tests decide what was paid. */
    static class FakeRazorpay implements IPaymentGateway {

        private final AtomicInteger orders = new AtomicInteger();
        private final Map<String, GatewayPayment> payments = new ConcurrentHashMap<>();
        final List<Long> orderAmounts = new CopyOnWriteArrayList<>();
        final List<String> captured = new CopyOnWriteArrayList<>();

        void reset() {
            orders.set(0);
            payments.clear();
            orderAmounts.clear();
            captured.clear();
        }

        void pay(String orderId, String paymentId, String status, long amount) {
            payments.put(paymentId, new GatewayPayment(paymentId, orderId, status, amount, "INR"));
        }

        @Override
        public String createOrder(long amount, String currency, String receipt) {
            orderAmounts.add(amount);
            return "order_test" + orders.incrementAndGet();
        }

        @Override
        public GatewayPayment fetchPayment(String paymentId) {
            GatewayPayment payment = payments.get(paymentId);
            if (payment == null) {
                throw new PaymentVerificationException("Razorpay refused the request: no such payment");
            }
            return payment;
        }

        @Override
        public List<GatewayPayment> fetchOrderPayments(String orderId) {
            return payments.values().stream().filter(p -> orderId.equals(p.orderId())).toList();
        }

        @Override
        public GatewayPayment capture(String paymentId, long amount, String currency) {
            GatewayPayment p = fetchPayment(paymentId);
            GatewayPayment done = new GatewayPayment(p.id(), p.orderId(), "captured", p.amount(), p.currency());
            payments.put(paymentId, done);
            captured.add(paymentId);
            return done;
        }
    }
}

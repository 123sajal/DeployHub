package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.assistant.AssistantBudget;
import com.deployhub.platform.config.BillingProperties;
import com.deployhub.platform.exceptions.PaymentRequiredException;
import com.deployhub.platform.exceptions.PaymentVerificationException;
import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.exceptions.ServiceUnavailableException;
import com.deployhub.platform.exceptions.TooManyRequestsException;
import com.deployhub.platform.models.DTO.BillingSummaryResponseDTO;
import com.deployhub.platform.models.DTO.PaymentOrderResponseDTO;
import com.deployhub.platform.models.DTO.PaymentVerifyRequestDTO;
import com.deployhub.platform.models.entities.Payment;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.PaymentStatus;
import com.deployhub.platform.repositories.PaymentRepository;
import com.deployhub.platform.repositories.UserRepository;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.IBillingService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IPaymentGateway;
import com.deployhub.platform.services.Interface.IPaymentGateway.GatewayPayment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Paid deploys. A user's first freeProjects deployed projects are free (redeploys included); every deploy of another project
 * uses one credit, and one Razorpay payment buys one credit. Rollbacks are free, and a deploy that fails or is
 * cancelled gives its credit back (DeploymentTransitionService).
 * <p>
 * Calls to Razorpay happen outside database transactions (they can take seconds); the database steps around them
 * are short and re-check what they change, so retried or parallel requests can't pay twice or credit twice.
 */
@Slf4j
@Service
public class BillingService implements IBillingService {

    private static final Pattern ORDER_ID = Pattern.compile("^order_[A-Za-z0-9]{1,30}$");
    /** Orders a user may start per hour: enough for a few failed attempts, not for flooding Razorpay. */
    private static final int MAX_ORDERS_PER_HOUR = 20;
    /** Unverified orders younger than this are checked with Razorpay (the browser may have closed after paying). */
    private static final Duration RECHECK_ORDERS_FOR = Duration.ofDays(1);

    private final BillingProperties properties;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final IPaymentGateway gateway;
    private final ICurrentUserService currentUserService;
    private final IAuditService auditService;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BillingService(BillingProperties properties, PaymentRepository paymentRepository,
                          UserRepository userRepository, IPaymentGateway gateway,
                          ICurrentUserService currentUserService, IAuditService auditService,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.gateway = gateway;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public BillingSummaryResponseDTO summary() {
        Long userId = currentUserService.currentUserId();
        if (properties.enabled() && properties.razorpay().configured()) {
            paymentRepository.findByUserIdAndStatusAndCreatedAtAfter(userId, PaymentStatus.CREATED,
                    clock.instant().minus(RECHECK_ORDERS_FOR)).forEach(this::recheck);
        }
        return summaryFor(userId);
    }

    @Override
    public PaymentOrderResponseDTO createOrder() {
        requirePayments();
        Long userId = currentUserService.currentUserId();
        UserMaster user = userRepository.findById(userId).orElseThrow();
        if (paymentRepository.countByUserIdAndCreatedAtAfter(userId, clock.instant().minus(Duration.ofHours(1)))
                >= MAX_ORDERS_PER_HOUR) {
            throw new TooManyRequestsException("Too many payment attempts. Try again later.", Duration.ofHours(1));
        }
        long amount = priceFor(user); // decided here, never by the browser
        String currency = properties.currency();
        String orderId = gateway.createOrder(amount, currency, "user-" + userId + "-" + clock.millis());
        tx.executeWithoutResult(status -> {
            Payment payment = new Payment();
            payment.setUserId(userId);
            payment.setRazorpayOrderId(orderId);
            payment.setAmountPaise(amount);
            payment.setCurrency(currency);
            payment.setStatus(PaymentStatus.CREATED);
            payment.setCreatedAt(clock.instant());
            paymentRepository.save(payment);
            auditService.record(AuditEventType.PAYMENT_ORDER_CREATED, userId, null, null, orderId);
        });
        return new PaymentOrderResponseDTO(orderId, properties.razorpay().keyId(), amount, currency,
                user.getDisplayName(), user.getEmail());
    }

    @Override
    public BillingSummaryResponseDTO verify(String orderId, PaymentVerifyRequestDTO request) {
        requirePayments();
        Long userId = currentUserService.currentUserId();
        Payment payment = (orderId != null && ORDER_ID.matcher(orderId).matches()
                ? paymentRepository.findByRazorpayOrderIdAndUserId(orderId, userId) : Optional.<Payment>empty())
                .orElseThrow(() -> new ResourceNotFoundException("Payment order was not found"));
        if (payment.getStatus() == PaymentStatus.PAID) {
            return summaryFor(userId); // already recorded (a retry, or the recheck got there first)
        }
        // Proves the browser didn't make these ids up: only Razorpay and this server know the secret.
        if (!signatureMatches(orderId, request.paymentId(), request.signature())) {
            throw new PaymentVerificationException("The payment signature doesn't match. Nothing was credited.");
        }
        // And Razorpay's own record decides whether money actually arrived, for this order and amount.
        GatewayPayment paid = settle(payment, gateway.fetchPayment(request.paymentId()));
        if (paid == null) {
            throw new PaymentVerificationException("Razorpay doesn't show this payment as completed for this order.");
        }
        recordPaid(payment, paid.id());
        return summaryFor(userId);
    }

    @Override
    public Optional<Payment> admitDeploy(Long userId, ProjectMaster project) {
        if (!properties.enabled() || project.isFree()) {
            return Optional.empty();
        }
        // One billing decision per user at a time: two deploys can't both claim the free project or one credit.
        UserMaster user = userRepository.findByIdForUpdate(userId).orElseThrow();
        if (user.getFreeProjectsUsed() < properties.freeProjects()) {
            Instant now = clock.instant();
            user.setFreeProjectsUsed(user.getFreeProjectsUsed() + 1);
            user.setFreeProjectClaimedAt(now);
            project.setFreeSince(now);
            auditService.record(AuditEventType.FREE_PROJECT_CLAIMED, userId, null, null,
                    "project #" + project.getId());
            return Optional.empty();
        }
        return Optional.of(paymentRepository.findFirstByUserIdAndStatusAndUsedAtIsNullOrderByIdAsc(userId,
                PaymentStatus.PAID).orElseThrow(() -> new PaymentRequiredException(
                freeProjectsUsedText() + " Each deploy of another project costs " + price(priceFor(user))
                        + (properties.razorpay().configured() ? "." : ", but payments aren't set up on this DeployHub yet."),
                priceFor(user), properties.currency())));
    }

    @Override
    public void useCredit(Payment credit, Long deploymentId) {
        credit.setUsedAt(clock.instant());
        credit.setDeploymentId(deploymentId);
        paymentRepository.save(credit);
        auditService.record(AuditEventType.DEPLOY_CREDIT_USED, credit.getUserId(), null, null,
                "deployment #" + deploymentId + " (order " + credit.getRazorpayOrderId() + ")");
    }

    /** A recent unverified order: credit it if Razorpay shows a completed payment for it. Never fails the caller. */
    private void recheck(Payment payment) {
        try {
            gateway.fetchOrderPayments(payment.getRazorpayOrderId()).stream()
                    .map(attempt -> settle(payment, attempt))
                    .filter(p -> p != null)
                    .findFirst()
                    .ifPresent(paid -> recordPaid(payment, paid.id()));
        } catch (RuntimeException e) {
            log.warn("Could not check order {} with Razorpay: {}", payment.getRazorpayOrderId(), e.getMessage());
        }
    }

    /**
     * The payment if it is for this order and amount and the money is taken (capturing it first if it is only
     * authorized); null otherwise.
     */
    private GatewayPayment settle(Payment payment, GatewayPayment attempt) {
        if (!payment.getRazorpayOrderId().equals(attempt.orderId()) || attempt.amount() != payment.getAmountPaise()
                || !payment.getCurrency().equals(attempt.currency())) {
            return null;
        }
        if (attempt.authorized()) {
            attempt = gateway.capture(attempt.id(), payment.getAmountPaise(), payment.getCurrency());
        }
        return attempt.captured() ? attempt : null;
    }

    private void recordPaid(Payment payment, String razorpayPaymentId) {
        tx.executeWithoutResult(status -> {
            if (paymentRepository.markPaid(payment.getId(), razorpayPaymentId, clock.instant()) == 1) {
                auditService.record(AuditEventType.PAYMENT_RECEIVED, payment.getUserId(), null, null,
                        payment.getRazorpayOrderId() + " / " + razorpayPaymentId);
            }
        });
    }

    private boolean signatureMatches(String orderId, String paymentId, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.razorpay().keySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature)); // constant time
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private void requirePayments() {
        if (!properties.enabled()) {
            throw new PaymentVerificationException("Deploys are free on this DeployHub; there is nothing to pay.");
        }
        if (!properties.razorpay().configured()) {
            throw new ServiceUnavailableException("Payments aren't set up on this DeployHub yet "
                    + "(RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET are missing).");
        }
    }

    private BillingSummaryResponseDTO summaryFor(Long userId) {
        UserMaster user = userRepository.findById(userId).orElseThrow();
        int left = Math.max(0, properties.freeProjects() - user.getFreeProjectsUsed());
        boolean student = verifiedStudent(user);
        return new BillingSummaryResponseDTO(properties.enabled(), properties.razorpay().configured(),
                properties.pricePerDeploy(), properties.currency(), paymentRepository.countUnusedCredits(userId),
                left > 0, properties.freeProjects(), left, priceFor(user), student,
                properties.pricePerDeploy() - properties.priceFor(true));
    }

    private String freeProjectsUsedText() {
        int free = properties.freeProjects();
        return free == 1 ? "Your free project is used." : "Your " + free + " free projects are used.";
    }

    /** Verified students (on today's date, India time) get the student discount. */
    private boolean verifiedStudent(UserMaster user) {
        return user.isVerifiedStudentOn(LocalDate.now(clock.withZone(AssistantBudget.INDIA)));
    }

    private long priceFor(UserMaster user) {
        return properties.priceFor(verifiedStudent(user));
    }

    private String price(long amount) {
        String value = amount % 100 == 0 ? String.valueOf(amount / 100) : String.format("%d.%02d", amount / 100, amount % 100);
        return "INR".equals(properties.currency()) ? "₹" + value : value + " " + properties.currency();
    }
}

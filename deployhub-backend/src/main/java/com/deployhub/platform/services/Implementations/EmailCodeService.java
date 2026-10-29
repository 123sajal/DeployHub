package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.config.PasswordResetProperties;
import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.exceptions.TooManyRequestsException;
import com.deployhub.platform.models.entities.EmailCode;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.EmailCodePurpose;
import com.deployhub.platform.repositories.AuditEventRepository;
import com.deployhub.platform.repositories.EmailCodeRepository;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.IEmailCodeService;
import com.deployhub.platform.services.Interface.IMailService;
import jakarta.annotation.PreDestroy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Codes that prove an email address, built like the password reset codes: only a hash is stored, a code works once,
 * for a short time, with a few guesses, and sending is limited per email and per IP (same settings as password reset).
 * Not @Transactional as a whole: a wrong guess must be counted even though the request then fails.
 */
@Service
public class EmailCodeService implements IEmailCodeService {

    static final String WRONG_CODE = "The code is wrong or has expired. Check the email or ask for a new code.";

    private final EmailCodeRepository codeRepository;
    private final AuditEventRepository auditEvents;
    private final IAuditService auditService;
    private final IMailService mailService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetProperties limits;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService mailExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public EmailCodeService(EmailCodeRepository codeRepository, AuditEventRepository auditEvents,
                            IAuditService auditService, IMailService mailService, PasswordEncoder passwordEncoder,
                            PasswordResetProperties limits, PlatformTransactionManager transactionManager, Clock clock) {
        this.codeRepository = codeRepository;
        this.auditEvents = auditEvents;
        this.auditService = auditService;
        this.mailService = mailService;
        this.passwordEncoder = passwordEncoder;
        this.limits = limits;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @PreDestroy
    void stop() {
        mailExecutor.close();
    }

    @Override
    public void send(EmailCodePurpose purpose, String email, Long userId, String ip) {
        Instant since = clock.instant().minus(limits.requestWindow());
        if (auditEvents.countByTypeAndSubjectAndOccurredAtAfter(AuditEventType.EMAIL_CODE_SENT, email, since)
                >= limits.maxRequestsPerEmail()
                || auditEvents.countByTypeAndIpAddressAndOccurredAtAfter(AuditEventType.EMAIL_CODE_SENT, ip, since)
                >= limits.maxRequestsPerIp()) {
            auditService.record(AuditEventType.EMAIL_CODE_THROTTLED, userId, email, ip, purpose.name());
            throw new TooManyRequestsException("Too many codes requested. Try again later.", limits.requestWindow());
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        String codeHash = passwordEncoder.encode(code);
        tx.executeWithoutResult(status -> {
            codeRepository.deleteByEmailAndPurpose(email, purpose);
            Instant now = clock.instant();
            EmailCode row = new EmailCode();
            row.setEmail(email);
            row.setPurpose(purpose);
            row.setUserId(userId);
            row.setCodeHash(codeHash);
            row.setCreatedAt(now);
            row.setExpiresAt(now.plus(limits.codeTtl()));
            codeRepository.save(row);
            auditService.record(AuditEventType.EMAIL_CODE_SENT, userId, email, ip, purpose.name());
        });
        String what = purpose == EmailCodePurpose.SIGNUP ? "confirm your email and create your account"
                : "confirm your college email for the student discount";
        mailExecutor.execute(() -> mailService.sendEmailCode(email, what, code, limits.codeTtl()));
    }

    @Override
    public void verify(EmailCodePurpose purpose, String email, Long userId, String code, String ip) {
        if (auditEvents.countByTypeAndIpAddressAndOccurredAtAfter(AuditEventType.EMAIL_CODE_FAILED, ip,
                clock.instant().minus(limits.requestWindow())) >= limits.maxFailuresPerIp()) {
            auditService.record(AuditEventType.EMAIL_CODE_THROTTLED, userId, email, ip, purpose.name());
            throw new TooManyRequestsException("Too many wrong codes. Try again later.", limits.requestWindow());
        }
        // A college email code belongs to the account that asked for it; another account can't use it.
        Optional<EmailCode> found = codeRepository.findFirstByEmailAndPurposeOrderByIdDesc(email, purpose)
                .filter(c -> Objects.equals(c.getUserId(), userId));
        if (found.isEmpty()) {
            throw wrongCode(purpose, email, userId, ip);
        }
        EmailCode row = found.get();
        Integer counted = tx.execute(status ->
                codeRepository.countAttempt(row.getId(), clock.instant(), limits.maxAttempts()));
        if (counted == null || counted == 0 || !passwordEncoder.matches(code, row.getCodeHash())) {
            throw wrongCode(purpose, email, userId, ip);
        }
        Integer used = tx.execute(status -> codeRepository.markUsed(row.getId(), clock.instant()));
        if (used == null || used == 0) {
            throw wrongCode(purpose, email, userId, ip); // a parallel request used it first
        }
    }

    private FieldValidationException wrongCode(EmailCodePurpose purpose, String email, Long userId, String ip) {
        auditService.record(AuditEventType.EMAIL_CODE_FAILED, userId, email, ip, purpose.name());
        return new FieldValidationException("code", WRONG_CODE);
    }
}

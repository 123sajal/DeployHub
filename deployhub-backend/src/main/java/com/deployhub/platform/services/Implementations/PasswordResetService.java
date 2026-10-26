package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.config.PasswordResetProperties;
import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.models.DTO.ForgotPasswordRequestDTO;
import com.deployhub.platform.models.DTO.ResetPasswordRequestDTO;
import com.deployhub.platform.models.entities.PasswordResetCode;
import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.models.enums.AccountStatus;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.PasswordResetCodeRepository;
import com.deployhub.platform.repositories.UserRepository;
import com.deployhub.platform.security.LoginThrottleService;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.IMailService;
import com.deployhub.platform.services.Interface.IPasswordResetService;
import jakarta.annotation.PreDestroy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Forgot password with an emailed 6-digit code. Not @Transactional as a whole: a wrong guess must be
 * counted (committed) even though the request then fails.
 */
@Service
public class PasswordResetService implements IPasswordResetService {

    static final String WRONG_CODE = "The code is wrong or has expired. Check the email or request a new code.";

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository codeRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottleService throttle;
    private final IAuditService auditService;
    private final IMailService mailService;
    private final PasswordResetProperties settings;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** Hash of a random value, compared against when there is no code, so both cases take as long. */
    private final String dummyHash;
    /** Email is sent in the background: the response time must not reveal whether the account exists. */
    private final ExecutorService mailExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public PasswordResetService(UserRepository userRepository, PasswordResetCodeRepository codeRepository,
                                PasswordEncoder passwordEncoder, LoginThrottleService throttle,
                                IAuditService auditService, IMailService mailService,
                                PasswordResetProperties settings, PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.userRepository = userRepository;
        this.codeRepository = codeRepository;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.auditService = auditService;
        this.mailService = mailService;
        this.settings = settings;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("%06d".formatted(random.nextInt(1_000_000)));
    }

    @PreDestroy
    void stop() {
        mailExecutor.close(); // lets emails already handed over finish
    }

    @Override
    public void requestCode(ForgotPasswordRequestDTO request, String ip) {
        throttle.checkPasswordResetRequest(request.email(), ip);
        String code = "%06d".formatted(random.nextInt(1_000_000));
        String codeHash = passwordEncoder.encode(code); // also for unknown emails, so both take as long
        Optional<UserMaster> account = activeAccount(request.email());

        transactionTemplate.executeWithoutResult(status -> {
            auditService.record(AuditEventType.PASSWORD_RESET_REQUESTED, account.map(UserMaster::getId).orElse(null),
                    request.email(), ip, null);
            account.ifPresent(user -> {
                codeRepository.deleteByUserId(user.getId()); // a new code replaces the old one
                Instant now = clock.instant();
                PasswordResetCode reset = new PasswordResetCode();
                reset.setUserId(user.getId());
                reset.setCodeHash(codeHash);
                reset.setCreatedAt(now);
                reset.setExpiresAt(now.plus(settings.codeTtl()));
                codeRepository.save(reset);
            });
        });
        account.ifPresent(user -> mailExecutor.execute(() ->
                mailService.sendPasswordResetCode(user.getEmail(), user.getDisplayName(), code, settings.codeTtl())));
    }

    @Override
    public void resetPassword(ResetPasswordRequestDTO request, String ip) {
        throttle.checkPasswordResetAttempt(ip);
        if (request.newPassword().trim().equalsIgnoreCase(request.email())) {
            throw new FieldValidationException("newPassword", "must not be the same as your email");
        }
        Optional<UserMaster> account = activeAccount(request.email());
        Optional<PasswordResetCode> reset = account.flatMap(user -> codeRepository.findFirstByUserIdOrderByIdDesc(user.getId()));
        if (reset.isEmpty()) {
            passwordEncoder.matches(request.code(), dummyHash);
            throw wrongCode(request.email(), null, ip);
        }
        Long userId = account.get().getId();
        PasswordResetCode code = reset.get();

        Integer counted = transactionTemplate.execute(status ->
                codeRepository.countAttempt(code.getId(), clock.instant(), settings.maxAttempts()));
        if (counted == null || counted == 0 || !passwordEncoder.matches(request.code(), code.getCodeHash())) {
            throw wrongCode(request.email(), userId, ip);
        }

        Boolean changed = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            if (codeRepository.markUsed(code.getId(), now) == 0) {
                return false; // a parallel request used it first
            }
            UserMaster user = userRepository.findById(userId).orElseThrow();
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            user.setUpdatedAt(now);
            auditService.record(AuditEventType.PASSWORD_RESET, userId, request.email(), ip, null);
            return true;
        });
        if (!Boolean.TRUE.equals(changed)) {
            throw wrongCode(request.email(), userId, ip);
        }
    }

    /** Only active accounts can reset (disabled ones stay disabled). */
    private Optional<UserMaster> activeAccount(String email) {
        return userRepository.findByEmail(email).filter(user -> user.getStatus() == AccountStatus.ACTIVE);
    }

    private FieldValidationException wrongCode(String email, Long userId, String ip) {
        auditService.record(AuditEventType.PASSWORD_RESET_FAILED, userId, email, ip, null);
        return new FieldValidationException("code", WRONG_CODE);
    }
}

package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.assistant.AssistantBudget;
import com.deployhub.platform.assistant.AssistantModel;
import com.deployhub.platform.assistant.AssistantModel.Role;
import com.deployhub.platform.assistant.AssistantModel.Turn;
import com.deployhub.platform.assistant.AssistantPrompts;
import com.deployhub.platform.assistant.AssistantUnavailableException;
import com.deployhub.platform.assistant.EmailProofs;
import com.deployhub.platform.assistant.EmailTypos;
import com.deployhub.platform.assistant.OfflineReplies;
import com.deployhub.platform.assistant.SecretMasker;
import com.deployhub.platform.config.AssistantProperties;
import com.deployhub.platform.exceptions.ConflictException;
import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.models.DTO.AssistantChatRequestDTO;
import com.deployhub.platform.models.DTO.AssistantExplainRequestDTO;
import com.deployhub.platform.models.DTO.AssistantReplyResponseDTO;
import com.deployhub.platform.models.DTO.AssistantReplyResponseDTO.Source;
import com.deployhub.platform.models.DTO.AssistantStatusResponseDTO;
import com.deployhub.platform.models.DTO.EmailCodeRequestDTO;
import com.deployhub.platform.models.DTO.EmailCodeVerifyRequestDTO;
import com.deployhub.platform.models.DTO.EmailProofResponseDTO;
import com.deployhub.platform.models.DTO.SignupCheckRequestDTO;
import com.deployhub.platform.models.DTO.SignupCheckResponseDTO;
import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.DeploymentExplanation;
import com.deployhub.platform.models.entities.DeploymentLog;
import com.deployhub.platform.models.entities.ProjectEnvVar;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.AssistantTopic;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.EmailCodePurpose;
import com.deployhub.platform.repositories.DeploymentExplanationRepository;
import com.deployhub.platform.repositories.DeploymentLogRepository;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.repositories.ProjectEnvVarRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.repositories.UserRepository;
import com.deployhub.platform.security.AuthenticatedUser;
import com.deployhub.platform.security.SecretCipher;
import com.deployhub.platform.services.Interface.IAssistantService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IEmailCodeService;
import com.deployhub.platform.utils.PersonName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The AI assistant. The AI only writes text: it gets DeployHub's real data (settings, the end of a log, the error the
 * page showed) with secrets masked, and can't change anything. Every answer has a built-in fallback, so the assistant
 * still helps without an API key, over the daily budget, or when the AI is down. Chats aren't stored; the browser
 * keeps the conversation. Calls to the AI happen outside database transactions (they take seconds).
 */
@Slf4j
@Service
public class AssistantService implements IAssistantService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** Fits the explanation column (VARCHAR2(4000) bytes) with room to spare for multi-byte characters. */
    private static final int MAX_SAVED_BYTES = 3900;

    private final AssistantModel model;
    private final AssistantBudget budget;
    private final AssistantPrompts prompts;
    private final AssistantProperties properties;
    private final EmailProofs emailProofs;
    private final IEmailCodeService emailCodes;
    private final ICurrentUserService currentUserService;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogRepository logRepository;
    private final DeploymentExplanationRepository explanationRepository;
    private final ProjectEnvVarRepository envVarRepository;
    private final SecretCipher cipher;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Deployments being explained right now: a double click mustn't pay for two explanations. */
    private final Set<Long> explaining = ConcurrentHashMap.newKeySet();

    public AssistantService(AssistantModel model, AssistantBudget budget, AssistantPrompts prompts,
                            AssistantProperties properties, EmailProofs emailProofs, IEmailCodeService emailCodes,
                            ICurrentUserService currentUserService, UserRepository userRepository,
                            ProjectRepository projectRepository, DeploymentRepository deploymentRepository,
                            DeploymentLogRepository logRepository,
                            DeploymentExplanationRepository explanationRepository,
                            ProjectEnvVarRepository envVarRepository, SecretCipher cipher,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.model = model;
        this.budget = budget;
        this.prompts = prompts;
        this.properties = properties;
        this.emailProofs = emailProofs;
        this.emailCodes = emailCodes;
        this.currentUserService = currentUserService;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.deploymentRepository = deploymentRepository;
        this.logRepository = logRepository;
        this.explanationRepository = explanationRepository;
        this.envVarRepository = envVarRepository;
        this.cipher = cipher;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public AssistantStatusResponseDTO status() {
        boolean available = budget.available();
        return new AssistantStatusResponseDTO(available, available && properties.mayReadIdCards());
    }

    @Override
    public SignupCheckResponseDTO checkSignup(SignupCheckRequestDTO request) {
        String value = request.value() == null ? "" : request.value().strip();
        return switch (request.field()) {
            case NAME -> checkName(PersonName.normalize(value));
            case EMAIL -> checkEmail(value.toLowerCase());
        };
    }

    private static SignupCheckResponseDTO checkName(String name) {
        if (name.isEmpty()) {
            return new SignupCheckResponseDTO(false, name, "Please tell me your name.", null);
        }
        if (name.length() > 100) {
            return new SignupCheckResponseDTO(false, name, "That's a bit long. Please use at most 100 characters.", null);
        }
        if (name.contains("@")) {
            return new SignupCheckResponseDTO(false, name,
                    "That looks like an email address. I'll ask for your email next; first, what's your name?", null);
        }
        if (!PersonName.isValid(name)) {
            return new SignupCheckResponseDTO(false, name, "Please use only letters and spaces in your name, without "
                    + "numbers or special characters (for example: Sajal Tomar).", null);
        }
        String first = name.split("\\s+")[0];
        return new SignupCheckResponseDTO(true, name, "Nice to meet you, " + first + "! What's your email address?", null);
    }

    private static SignupCheckResponseDTO checkEmail(String email) {
        if (email.isEmpty()) {
            return new SignupCheckResponseDTO(false, email, "Please type your email address.", null);
        }
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            return new SignupCheckResponseDTO(false, email,
                    "That doesn't look like an email address. It should look like name@example.com.", null);
        }
        Optional<String> typo = EmailTypos.suggest(email);
        if (typo.isPresent()) {
            // Only a suggestion: the address may be right (a company domain), so the user can keep it.
            return new SignupCheckResponseDTO(true, email, "Did you mean " + typo.get() + "?", typo.get());
        }
        return new SignupCheckResponseDTO(true, email, "Thanks! I'll send a 6-digit code to " + email
                + " to make sure it's yours.", null);
    }

    @Override
    public void sendSignupCode(EmailCodeRequestDTO request, String ip) {
        // Sign-up already says "already has an account" (rate-limited per IP); saying it here, before a code is
        // sent, saves the user from typing a code for nothing. Sending is limited per email and IP as well.
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new ConflictException("This email already has a DeployHub account. Log in instead, or use "
                    + "Forgot password if you don't remember the password.");
        }
        emailCodes.send(EmailCodePurpose.SIGNUP, request.email(), null, ip);
    }

    @Override
    public EmailProofResponseDTO verifySignupCode(EmailCodeVerifyRequestDTO request, String ip) {
        emailCodes.verify(EmailCodePurpose.SIGNUP, request.email(), null, request.code(), ip);
        EmailProofs.Proof proof = emailProofs.issue(request.email());
        return new EmailProofResponseDTO(request.email(), proof.value(), proof.expiresAt());
    }

    @Override
    public AssistantReplyResponseDTO chat(AssistantChatRequestDTO request, String ip) {
        List<AssistantChatRequestDTO.Turn> messages = request.messages();
        AssistantChatRequestDTO.Turn last = messages.getLast();
        if (last.role() != AssistantChatRequestDTO.Role.USER) {
            throw new FieldValidationException("messages", "must end with the user's question");
        }
        Optional<AuthenticatedUser> user = signedInUser();
        String subject = user.map(u -> AssistantBudget.userSubject(u.getId()))
                .orElse(AssistantBudget.ipSubject(ip));
        int limit = user.isPresent() ? properties.maxRequestsPerUserPerDay() : properties.maxRequestsPerIpPerDay();

        Optional<AssistantBudget.Limit> refusal = budget.check(subject, limit);
        if (refusal.isPresent()) {
            return builtIn(OfflineReplies.chat(last.text()), refusal.get());
        }
        List<Turn> turns = new ArrayList<>();
        String page = request.page() == null || request.page().isBlank() ? "unknown" : request.page().strip();
        for (int i = 0; i < messages.size(); i++) {
            AssistantChatRequestDTO.Turn turn = messages.get(i);
            String text = SecretMasker.mask(turn.text());
            if (i == messages.size() - 1) {
                text = "<page>" + page + (user.isPresent() ? ", signed in" : ", not signed in") + "</page>\n" + text;
            }
            turns.add(new Turn(turn.role() == AssistantChatRequestDTO.Role.USER ? Role.USER : Role.ASSISTANT, text));
        }
        return askAi(subject, new AssistantModel.Request(properties.chatModel(), prompts.chat(), alternate(turns),
                1500, false), () -> OfflineReplies.chat(last.text()));
    }

    @Override
    public AssistantReplyResponseDTO explain(AssistantExplainRequestDTO request) {
        Long userId = currentUserService.currentUserId();
        if (request.topic() == AssistantTopic.DEPLOYMENT_FAILED) {
            return explainDeployment(userId, request.deploymentId());
        }
        String subject = AssistantBudget.userSubject(userId);
        String errorText = request.errorText() == null ? "" : request.errorText().strip();
        StringBuilder question = new StringBuilder("Topic: ").append(request.topic()).append('\n');
        if (request.projectId() != null) {
            ProjectMaster project = projectRepository.findByIdAndOwnerId(request.projectId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project", request.projectId()));
            question.append(projectDescription(project)).append('\n');
        }
        question.append("<error>").append(SecretMasker.mask(errorText)).append("</error>");

        Optional<AssistantBudget.Limit> refusal = budget.check(subject, properties.maxRequestsPerUserPerDay());
        if (refusal.isPresent()) {
            return builtIn(OfflineReplies.explain(request.topic(), errorText), refusal.get());
        }
        return askAi(subject, new AssistantModel.Request(properties.chatModel(), prompts.explainError(),
                List.of(new Turn(Role.USER, question.toString())), 1500, false),
                () -> OfflineReplies.explain(request.topic(), errorText));
    }

    private record FailureFacts(String question, String failureReason) {
    }

    private AssistantReplyResponseDTO explainDeployment(Long userId, Long deploymentId) {
        if (deploymentId == null) {
            throw new FieldValidationException("deploymentId", "is required to explain a deployment");
        }
        FailureFacts facts = tx.execute(status -> {
            Deployment deployment = deploymentRepository.findOwned(deploymentId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Deployment", deploymentId));
            if (deployment.getStatus() != DeploymentStatus.FAILED) {
                throw new ConflictException("Only failed deployments can be explained.");
            }
            return failureFacts(deployment);
        });
        Optional<DeploymentExplanation> saved = explanationRepository.findById(deploymentId);
        if (saved.isPresent()) {
            return new AssistantReplyResponseDTO(saved.get().getExplanation(), Source.AI, null, true);
        }
        String subject = AssistantBudget.userSubject(userId);
        Optional<AssistantBudget.Limit> refusal = budget.check(subject, properties.maxRequestsPerUserPerDay());
        if (refusal.isPresent()) {
            return builtIn(OfflineReplies.explain(AssistantTopic.DEPLOYMENT_FAILED, facts.failureReason()), refusal.get());
        }
        if (!explaining.add(deploymentId)) {
            throw new ConflictException("The assistant is already reading this deployment's log. Wait a moment.");
        }
        try {
            String model = properties.explainModel();
            AssistantReplyResponseDTO reply = askAi(subject, new AssistantModel.Request(model,
                            prompts.explainDeployment(), List.of(new Turn(Role.USER, facts.question())), 8000, true),
                    () -> OfflineReplies.explain(AssistantTopic.DEPLOYMENT_FAILED, facts.failureReason()));
            if (reply.source() == Source.AI) {
                String text = fitBytes(reply.text());
                save(deploymentId, text, model);
                return new AssistantReplyResponseDTO(text, Source.AI, null, false);
            }
            return reply;
        } finally {
            explaining.remove(deploymentId);
        }
    }

    /** What the AI needs to explain a failure, secrets masked. Runs inside a transaction (lazy project). */
    private FailureFacts failureFacts(Deployment deployment) {
        ProjectMaster project = deployment.getProject();
        List<String> secrets = new ArrayList<>();
        for (ProjectEnvVar envVar : envVarRepository.findByProjectIdOrderByNameAsc(project.getId())) {
            decrypt(envVar.getValueEncrypted()).ifPresent(secrets::add);
        }
        decrypt(project.getRepoTokenEncrypted()).ifPresent(secrets::add);

        List<DeploymentLog> newestFirst = logRepository.findByDeploymentIdOrderByIdDesc(deployment.getId(),
                Limit.of(properties.maxLogLines()));
        StringBuilder log = new StringBuilder();
        for (DeploymentLog line : newestFirst.reversed()) {
            log.append('[').append(line.getLevel()).append("] ").append(line.getMessage()).append('\n');
        }
        String question = projectDescription(project) + "\n"
                + "Deployment #" + deployment.getId() + (deployment.getRollbackOfId() != null ? " (a rollback)" : "")
                + ", mode " + deployment.getExecutionMode() + "\n"
                + "<error>" + (deployment.getFailureReason() == null ? "" : deployment.getFailureReason()) + "</error>\n"
                + "<log>\n" + log + "</log>";
        return new FailureFacts(SecretMasker.mask(question, secrets), deployment.getFailureReason());
    }

    private static String projectDescription(ProjectMaster project) {
        return "<project>name: " + project.getName()
                + "\napplication type: " + project.getAppType()
                + "\nApp folder: " + (project.getRootDirectory() == null ? "(repository root)" : project.getRootDirectory())
                + "\nbranch: " + project.getBranch()
                + "\nBuild command: " + nullToNone(project.getBuildCommand())
                + "\nStart command: " + nullToNone(project.getStartCommand())
                + "\nprivate repository: " + (project.getRepoTokenEncrypted() != null)
                + "</project>";
    }

    private static String nullToNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private Optional<String> decrypt(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(cipher.decrypt(stored));
        } catch (RuntimeException e) {
            return Optional.empty(); // unreadable (key changed): it can't appear in logs in plain text either
        }
    }

    private void save(Long deploymentId, String text, String model) {
        try {
            tx.executeWithoutResult(status -> {
                DeploymentExplanation explanation = new DeploymentExplanation();
                explanation.setDeploymentId(deploymentId);
                explanation.setExplanation(text);
                explanation.setModel(model);
                explanation.setCreatedAt(clock.instant());
                explanationRepository.saveAndFlush(explanation);
            });
        } catch (DataIntegrityViolationException e) {
            // Saved by a parallel request, or the deployment was deleted meanwhile: nothing to keep.
            log.debug("Explanation for deployment {} not saved: {}", deploymentId, e.getMessage());
        }
    }

    /** Calls the AI and counts the cost; any problem (or a refusal or empty answer) gives the built-in reply. */
    private AssistantReplyResponseDTO askAi(String subject, AssistantModel.Request request,
                                            java.util.function.Supplier<String> fallback) {
        AssistantModel.Reply reply;
        try {
            reply = model.ask(request);
        } catch (AssistantUnavailableException e) {
            // The explain model may be busy while the lighter chat model still has room: try that before giving up.
            if (request.model().equals(properties.chatModel())) {
                return builtIn(fallback.get(), null);
            }
            log.info("{} didn't answer ({}); trying {}", request.model(), e.getMessage(), properties.chatModel());
            request = new AssistantModel.Request(properties.chatModel(), request.system(), request.turns(),
                    request.maxTokens(), request.thorough());
            try {
                reply = model.ask(request);
            } catch (AssistantUnavailableException again) {
                return builtIn(fallback.get(), null);
            }
        }
        budget.record(subject, request.model(), reply.usage());
        if (reply.refused() || reply.text().isBlank()) {
            return builtIn(fallback.get(), null);
        }
        String text = reply.cutOff() ? reply.text() + " …" : reply.text();
        return new AssistantReplyResponseDTO(text, Source.AI, null, false);
    }

    private static AssistantReplyResponseDTO builtIn(String text, AssistantBudget.Limit limit) {
        return new AssistantReplyResponseDTO(text, Source.BUILT_IN, OfflineReplies.notice(limit), false);
    }

    /**
     * The API needs user and assistant turns to alternate and the first to be the user's; the browser's history
     * may start with the assistant's greeting or repeat a role, so turns are merged and leading ones dropped.
     */
    private static List<Turn> alternate(List<Turn> turns) {
        List<Turn> result = new ArrayList<>();
        for (Turn turn : turns) {
            if (result.isEmpty() && turn.role() != Role.USER) {
                continue;
            }
            if (!result.isEmpty() && result.getLast().role() == turn.role()) {
                Turn previous = result.removeLast();
                result.add(new Turn(turn.role(), previous.text() + "\n\n" + turn.text()));
            } else {
                result.add(turn);
            }
        }
        return result;
    }

    private static String fitBytes(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= MAX_SAVED_BYTES) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > MAX_SAVED_BYTES - 4) {
                break;
            }
            out.appendCodePoint(codePoint);
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return out.append(" …").toString();
    }

    private static Optional<AuthenticatedUser> signedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? Optional.of(user) : Optional.empty();
    }
}

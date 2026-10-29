package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.assistant.AssistantBudget;
import com.deployhub.platform.assistant.AssistantModel;
import com.deployhub.platform.assistant.AssistantModel.IdCardReading;
import com.deployhub.platform.assistant.AssistantUnavailableException;
import com.deployhub.platform.config.AssistantProperties;
import com.deployhub.platform.config.StudentProperties;
import com.deployhub.platform.exceptions.ConflictException;
import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.exceptions.ServiceUnavailableException;
import com.deployhub.platform.exceptions.TooManyRequestsException;
import com.deployhub.platform.models.DTO.EmailCodeRequestDTO;
import com.deployhub.platform.models.DTO.EmailCodeVerifyRequestDTO;
import com.deployhub.platform.models.DTO.StudentVerificationResponseDTO;
import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.EmailCodePurpose;
import com.deployhub.platform.models.enums.Occupation;
import com.deployhub.platform.models.enums.StudentProof;
import com.deployhub.platform.repositories.AuditEventRepository;
import com.deployhub.platform.repositories.UserRepository;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IEmailCodeService;
import com.deployhub.platform.services.Interface.IStudentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The student check behind the student discount. Two ways: a student ID photo read by the AI, or a code sent to a
 * college email address. The photo stays in memory: it is re-encoded (which drops metadata such as GPS location),
 * sent to the AI once, and forgotten. Only "verified until" and how are saved.
 */
@Service
public class StudentService implements IStudentService {

    /** Claude reads images best up to about this size; larger photos only cost more. */
    private static final int MAX_EDGE = 1568;
    private static final String PHOTO_HELP = "Please upload a clear JPG or PNG photo of the front of your student ID card.";

    private final AssistantModel model;
    private final AssistantBudget budget;
    private final AssistantProperties assistant;
    private final StudentProperties properties;
    private final IEmailCodeService emailCodes;
    private final ICurrentUserService currentUserService;
    private final UserRepository userRepository;
    private final AuditEventRepository auditEvents;
    private final IAuditService auditService;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StudentService(AssistantModel model, AssistantBudget budget, AssistantProperties assistant,
                          StudentProperties properties, IEmailCodeService emailCodes,
                          ICurrentUserService currentUserService, UserRepository userRepository,
                          AuditEventRepository auditEvents, IAuditService auditService,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.model = model;
        this.budget = budget;
        this.assistant = assistant;
        this.properties = properties;
        this.emailCodes = emailCodes;
        this.currentUserService = currentUserService;
        this.userRepository = userRepository;
        this.auditEvents = auditEvents;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public StudentVerificationResponseDTO verifyIdCard(MultipartFile photo, boolean consent) {
        if (!assistant.mayReadIdCards()) {
            throw new ServiceUnavailableException("On this DeployHub the AI runs on Google's free tier, where Google may "
                    + "use what it receives to improve its products, so student ID photos are not sent. Use your "
                    + "college email instead.");
        }
        if (!consent) {
            throw new FieldValidationException("consent",
                    "Please agree that DeployHub may check the photo (it is deleted right after the check).");
        }
        UserMaster user = userRepository.findById(currentUserService.currentUserId()).orElseThrow();
        LocalDate today = budget.today();
        refuseIfAlreadyVerified(user, today);
        if (auditEvents.countByTypeAndSubjectAndOccurredAtAfter(AuditEventType.STUDENT_ID_CHECKED, user.getEmail(),
                clock.instant().minus(Duration.ofDays(1))) >= properties.maxIdChecksPerDay()) {
            throw new TooManyRequestsException("You've tried several photos today. Try again tomorrow, or use your "
                    + "college email instead.", Duration.ofDays(1));
        }
        byte[] jpeg = cleanJpeg(photo);

        String subject = AssistantBudget.userSubject(user.getId());
        Optional<AssistantBudget.Limit> limit = budget.check(subject, assistant.maxRequestsPerUserPerDay());
        if (limit.isPresent()) {
            throw switch (limit.get()) {
                case NOT_SET_UP -> new ServiceUnavailableException("The student ID check needs the AI assistant, "
                        + "which isn't set up on this DeployHub yet. Use your college email instead.");
                case DAILY_BUDGET -> new ServiceUnavailableException("The student ID check is paused until midnight "
                        + "(India time). Try again tomorrow, or use your college email instead.");
                case PERSONAL_LIMIT -> new TooManyRequestsException("You've used today's AI requests. Try again "
                        + "tomorrow, or use your college email instead.", Duration.ofDays(1));
            };
        }
        AssistantModel.IdCardResult result;
        try {
            result = model.readStudentId(assistant.chatModel(), jpeg);
        } catch (AssistantUnavailableException e) {
            throw new ServiceUnavailableException("The student ID check isn't answering right now. Try again in a few "
                    + "minutes, or use your college email instead.", e);
        }
        budget.record(subject, assistant.chatModel(), result.usage());
        auditService.record(AuditEventType.STUDENT_ID_CHECKED, user.getId(), user.getEmail(), null, null);

        if (result.refused() || result.reading() == null) {
            throw new FieldValidationException("file", "This photo couldn't be checked. " + PHOTO_HELP);
        }
        IdCardReading reading = result.reading();
        if (!reading.readable() || !reading.studentIdCard()) {
            String problem = reading.problem() == null || reading.problem().isBlank()
                    ? (reading.readable() ? "This doesn't look like a student ID card." : "The card is hard to read.")
                    : reading.problem().strip();
            throw new FieldValidationException("file", problem + " " + PHOTO_HELP);
        }
        if (!namesMatch(user.getDisplayName(), reading.nameOnCard())) {
            String onCard = reading.nameOnCard() == null || reading.nameOnCard().isBlank() ? "no readable name"
                    : "\"" + reading.nameOnCard().strip() + "\"";
            throw new FieldValidationException("file", "The card shows " + onCard + ", which doesn't match your "
                    + "account name \"" + user.getDisplayName() + "\". Upload your own card, or use your college email.");
        }
        LocalDate until = validUntil(reading.validUntil(), today);
        return markVerified(user.getId(), until, StudentProof.ID_CARD,
                "You're a verified student" + institution(reading) + ". The student discount applies until "
                        + until + ".");
    }

    @Override
    public void sendCollegeEmailCode(EmailCodeRequestDTO request, String ip) {
        if (!isCollegeEmail(request.email())) {
            throw new FieldValidationException("email", "Use your college email address (ending in "
                    + String.join(", ", properties.collegeEmailSuffixes()) + ").");
        }
        UserMaster user = userRepository.findById(currentUserService.currentUserId()).orElseThrow();
        refuseIfAlreadyVerified(user, budget.today());
        emailCodes.send(EmailCodePurpose.STUDENT, request.email(), user.getId(), ip);
    }

    @Override
    public StudentVerificationResponseDTO verifyCollegeEmail(EmailCodeVerifyRequestDTO request, String ip) {
        Long userId = currentUserService.currentUserId();
        emailCodes.verify(EmailCodePurpose.STUDENT, request.email(), userId, request.code(), ip);
        LocalDate until = budget.today().plus(properties.defaultValidity());
        return markVerified(userId, until, StudentProof.COLLEGE_EMAIL,
                "Your college email is confirmed. The student discount applies until " + until + ".");
    }

    private void refuseIfAlreadyVerified(UserMaster user, LocalDate today) {
        if (user.isVerifiedStudentOn(today)) {
            throw new ConflictException("You're already a verified student until " + user.getStudentVerifiedUntil()
                    + ". You can check again after that date.");
        }
    }

    private StudentVerificationResponseDTO markVerified(Long userId, LocalDate until, StudentProof proof,
                                                        String message) {
        tx.executeWithoutResult(status -> {
            UserMaster user = userRepository.findById(userId).orElseThrow();
            user.setOccupation(Occupation.STUDENT);
            user.setStudentVerifiedUntil(until);
            user.setStudentVerifiedBy(proof);
            user.setUpdatedAt(clock.instant());
            auditService.record(AuditEventType.STUDENT_VERIFIED, userId, user.getEmail(), null,
                    proof + " until " + until);
        });
        return new StudentVerificationResponseDTO(true, until, proof, message);
    }

    boolean isCollegeEmail(String email) {
        String lower = email.toLowerCase(Locale.ROOT);
        return properties.collegeEmailSuffixes().stream().map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).anyMatch(lower::endsWith);
    }

    /** The card's date, capped at maxValidity ahead; a card without a date counts for defaultValidity. */
    private LocalDate validUntil(String printed, LocalDate today) {
        LocalDate latest = today.plus(properties.maxValidity());
        if (printed == null || printed.isBlank()) {
            return today.plus(properties.defaultValidity());
        }
        LocalDate date;
        try {
            date = LocalDate.parse(printed.strip());
        } catch (DateTimeParseException e) {
            return today.plus(properties.defaultValidity());
        }
        if (date.isBefore(today)) {
            throw new FieldValidationException("file", "This card expired on " + date + ". Upload your current "
                    + "card, or use your college email instead.");
        }
        return date.isAfter(latest) ? latest : date;
    }

    /**
     * Lenient on purpose (cards print "SAJAL SINGH TOMAR", accounts say "Sajal"): one shared name part of at least
     * three letters is enough.
     */
    static boolean namesMatch(String accountName, String cardName) {
        if (accountName == null || cardName == null) {
            return false;
        }
        Set<String> card = nameParts(cardName);
        return nameParts(accountName).stream().filter(part -> part.length() >= 3).anyMatch(card::contains);
    }

    private static Set<String> nameParts(String name) {
        return Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^\\p{L}]+"))
                .filter(part -> !part.isEmpty()).collect(Collectors.toSet());
    }

    private static String institution(IdCardReading reading) {
        return reading.institution() == null || reading.institution().isBlank() ? ""
                : " at " + reading.institution().strip();
    }

    /**
     * Checks the photo really is a JPG or PNG (by its content, not its name), shrinks it and saves it again as a
     * JPEG. Re-encoding drops all metadata (camera, GPS location). Huge images are decoded at reduced size, so a
     * small file that unpacks to an enormous picture can't exhaust memory.
     */
    byte[] cleanJpeg(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            throw new FieldValidationException("file", PHOTO_HELP);
        }
        if (photo.getSize() > properties.maxIdCardBytes()) {
            throw new FieldValidationException("file", "The photo is larger than "
                    + properties.maxIdCardBytes() / (1024 * 1024) + " MB. Take it again at a lower size, or crop it.");
        }
        byte[] bytes;
        try {
            bytes = photo.getBytes();
        } catch (IOException e) {
            throw new FieldValidationException("file", PHOTO_HELP);
        }
        if (!isJpeg(bytes) && !isPng(bytes)) {
            throw new FieldValidationException("file", PHOTO_HELP);
        }
        BufferedImage image = decode(bytes);
        int width = image.getWidth();
        int height = image.getHeight();
        double scale = Math.min(1d, (double) MAX_EDGE / Math.max(width, height));
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE); // transparent PNG areas become white, not black
            g.fillRect(0, 0, w, h);
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return writeJpeg(out);
    }

    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new FieldValidationException("file", PHOTO_HELP);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) (MAX_EDGE * 2)));
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new FieldValidationException("file", PHOTO_HELP);
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof FieldValidationException field) {
                throw field;
            }
            throw new FieldValidationException("file", PHOTO_HELP);
        }
    }

    private static byte[] writeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Could not re-encode the photo", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }
}

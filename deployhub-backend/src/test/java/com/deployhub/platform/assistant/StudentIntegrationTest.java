package com.deployhub.platform.assistant;

import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.StudentProof;
import com.deployhub.platform.support.AssistantTestSupport;
import com.deployhub.platform.support.FakeAssistantModel;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The student check (ID photo read by a fake AI, or a college email code) and what it unlocks. */
class StudentIntegrationTest extends AssistantTestSupport {

    private static byte[] photo() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private ResultActions uploadCard(boolean consent) throws Exception {
        return mockMvc.perform(multipart("/api/students/verify/id-card")
                .file(new MockMultipartFile("file", "card.jpg", "image/jpeg", photo()))
                .param("consent", String.valueOf(consent)));
    }

    private LocalDate today() {
        return LocalDate.now(AssistantBudget.INDIA);
    }

    @Test
    void aReadableCardWithTheAccountsNameMakesAVerifiedStudentAndThePhotoIsNotKept() throws Exception {
        String until = today().plusMonths(8).toString();
        ai.idCardResult = FakeAssistantModel.card(true, true, "ALICE R EXAMPLE", "Delhi University", until, "");

        uploadCard(true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.validUntil").value(until))
                .andExpect(jsonPath("$.method").value("ID_CARD"))
                .andExpect(jsonPath("$.message").value(containsString("at Delhi University")));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(jsonPath("$.occupation").value("STUDENT"))
                .andExpect(jsonPath("$.studentVerifiedUntil").value(until))
                .andExpect(jsonPath("$.studentVerifiedBy").value("ID_CARD"));
        mockMvc.perform(get("/api/billing"))
                .andExpect(jsonPath("$.verifiedStudent").value(true))
                .andExpect(jsonPath("$.yourPrice").value(4400))
                .andExpect(jsonPath("$.studentDiscount").value(500));
        assertThat(ai.idCards).hasSize(1);
        assertThat(usageRepository.findAll()).singleElement().satisfies(u -> assertThat(u.getRequests()).isEqualTo(1));
        assertThat(auditEventRepository.findAll()).extracting(e -> e.getType())
                .contains(AuditEventType.STUDENT_ID_CHECKED, AuditEventType.STUDENT_VERIFIED);
        // Nothing in the database holds the picture: only the date and how it was checked.
        assertThat(userRepository.findById(alice.id())).hasValueSatisfying(u -> {
            assertThat(u.getStudentVerifiedBy()).isEqualTo(StudentProof.ID_CARD);
            assertThat(u.getStudentVerifiedUntil()).isEqualTo(LocalDate.parse(until));
        });
    }

    @Test
    void aCardWithoutADateCountsForOneYearAndFarDatesAreCapped() throws Exception {
        ai.idCardResult = FakeAssistantModel.card(true, true, "Alice", "", "", "");
        uploadCard(true).andExpect(jsonPath("$.validUntil").value(today().plusYears(1).toString()));

        var bob = registerUser("Bob Kumar", "bob@example.com");
        ai.idCardResult = FakeAssistantModel.card(true, true, "BOB KUMAR", "", "2099-12-31", "");
        mvcAs(bob).perform(multipart("/api/students/verify/id-card")
                        .file(new MockMultipartFile("file", "card.jpg", "image/jpeg", photo())).param("consent", "true"))
                .andExpect(jsonPath("$.validUntil").value(today().plusYears(4).toString()));
    }

    @Test
    void someoneElsesCardAnExpiredCardOrABadPhotoIsRefusedWithAHelpfulMessage() throws Exception {
        ai.idCardResult = FakeAssistantModel.card(true, true, "Rahul Verma", "IIT", "2030-01-01", "");
        uploadCard(true).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").value(containsString("\"Rahul Verma\", which doesn't match")));

        ai.idCardResult = FakeAssistantModel.card(true, true, "Alice", "IIT", today().minusDays(1).toString(), "");
        uploadCard(true).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").value(containsString("expired")));

        ai.idCardResult = FakeAssistantModel.card(false, false, "", "", "", "The photo is too blurry to read.");
        uploadCard(true).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").value(containsString("The photo is too blurry to read.")));

        ai.idCardResult = FakeAssistantModel.card(true, false, "", "", "", "");
        uploadCard(true).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").value(containsString("doesn't look like a student ID")));

        mockMvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.studentVerifiedUntil").doesNotExist());
        mockMvc.perform(get("/api/billing")).andExpect(jsonPath("$.yourPrice").value(4900));
    }

    @Test
    void consentIsRequiredAndOnlyAFewPhotosPerDayAreChecked() throws Exception {
        uploadCard(false).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.consent").exists());
        assertThat(ai.idCards).isEmpty();

        ai.idCardResult = FakeAssistantModel.card(true, true, "Someone Else", "", "", "");
        for (int i = 0; i < 5; i++) {
            uploadCard(true).andExpect(status().isBadRequest());
        }
        uploadCard(true).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(containsString("college email")));
        assertThat(ai.idCards).hasSize(5);
    }

    @Test
    void anAlreadyVerifiedStudentIsNotCheckedAgain() throws Exception {
        uploadCard(true).andExpect(status().isOk());
        uploadCard(true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already a verified student")));
        assertThat(ai.idCards).hasSize(1);
    }

    @Test
    void whenTheAiIsDownTheStudentIsPointedToTheCollegeEmail() throws Exception {
        ai.down = true;
        uploadCard(true).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("college email")));
    }

    @Test
    void aCollegeEmailCodeVerifiesTheStudentForAYear() throws Exception {
        mockMvc.perform(post("/api/students/verify/college-email/code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@gmail.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").value(containsString(".ac.in")));

        mockMvc.perform(post("/api/students/verify/college-email/code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Alice@IITD.ac.in\"}"))
                .andExpect(status().isAccepted());
        String code = emailedCode("alice@iitd.ac.in", 1);

        // The code belongs to Alice's account: Bob can't use it.
        var bob = registerUser("Bob", "bob@example.com");
        mvcAs(bob).perform(post("/api/students/verify/college-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@iitd.ac.in\",\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/students/verify/college-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@iitd.ac.in\",\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("COLLEGE_EMAIL"))
                .andExpect(jsonPath("$.validUntil").value(today().plusYears(1).toString()));
        assertThat(ai.idCards).isEmpty(); // no AI needed for this way
    }

    @Test
    void theOccupationCanBeChangedLaterAndStudentEndpointsNeedASession() throws Exception {
        mockMvc.perform(patch("/api/auth/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occupation\":\"PROFESSIONAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupation").value("PROFESSIONAL"));
        mockMvc.perform(patch("/api/auth/me").contentType(MediaType.APPLICATION_JSON).content("{\"occupation\":\"CEO\"}"))
                .andExpect(status().isBadRequest());
        anonymousMvc.perform(multipart("/api/students/verify/id-card").file(
                        new MockMultipartFile("file", "card.jpg", "image/jpeg", photo())).with(xsrf()))
                .andExpect(status().isUnauthorized());
    }
}

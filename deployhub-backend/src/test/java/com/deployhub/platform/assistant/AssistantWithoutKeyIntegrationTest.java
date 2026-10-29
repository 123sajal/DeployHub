package com.deployhub.platform.assistant;

import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without an API key (the real Gemini client, never called): everything still answers, with built-in replies. */
class AssistantWithoutKeyIntegrationTest extends IntegrationTestSupport {

    @Test
    void theAssistantSaysItIsNotSetUpAndStillHelps() throws Exception {
        anonymousMvc.perform(get("/api/assistant/status"))
                .andExpect(jsonPath("$.aiAvailable").value(false))
                .andExpect(jsonPath("$.idCardCheck").value(false));
        anonymousMvc.perform(post("/api/assistant/chat").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":\"signup\",\"messages\":[{\"role\":\"USER\",\"text\":\"is there a student discount?\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("BUILT_IN"))
                .andExpect(jsonPath("$.notice").value(containsString("isn't set up")))
                .andExpect(jsonPath("$.text").value(containsString("Student discount")));
        anonymousMvc.perform(post("/api/assistant/signup/check").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"EMAIL\",\"value\":\"a@gmial.com\"}"))
                .andExpect(jsonPath("$.suggestion").value("a@gmail.com"));
    }

    @Test
    void explanationsFallBackToTheChecklistAndTheIdCheckPointsToTheCollegeEmail() throws Exception {
        mockMvc.perform(post("/api/assistant/explain").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"PAYMENT_FAILED\",\"errorText\":\"Payment failed\"}"))
                .andExpect(jsonPath("$.source").value("BUILT_IN"))
                .andExpect(jsonPath("$.text").value(containsString("No money is taken")));

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
        mockMvc.perform(multipart("/api/students/verify/id-card")
                        .file(new MockMultipartFile("file", "card.jpg", "image/jpeg", jpeg.toByteArray()))
                        .param("consent", "true"))
                .andExpect(status().isServiceUnavailable())
                // Gemini's free tier (the default) never gets student ID photos.
                .andExpect(jsonPath("$.detail").value(containsString("free tier")))
                .andExpect(jsonPath("$.detail").value(containsString("college email")));
    }
}

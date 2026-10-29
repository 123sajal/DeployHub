package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.config.StudentProperties;
import com.deployhub.platform.exceptions.FieldValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Period;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentServiceTest {

    private final StudentService service = new StudentService(null, null, null,
            new StudentProperties(List.of(".ac.in", ".edu", ".edu.in"), Period.ofYears(1), Period.ofYears(4),
                    5 * 1024 * 1024, 5), null, null, null, null, null, null, null);

    static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /** A JPEG with an EXIF block holding a (fake) GPS location, as phones write them. */
    static byte[] jpegWithLocation() throws IOException {
        byte[] jpeg = image("jpg", 200, 120);
        byte[] payload = "Exif\0\0GPS 28.6139N 77.2090E SECRET-LOCATION".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1); // APP1 = EXIF
        int length = payload.length + 2;
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    @Test
    void namesMatchWhenOneRealNamePartIsShared() {
        assertThat(StudentService.namesMatch("Sajal", "SAJAL SINGH TOMAR")).isTrue();
        assertThat(StudentService.namesMatch("S. Tomar", "Sajal Singh Tomar")).isTrue();
        assertThat(StudentService.namesMatch("Priya Sharma", "PRIYA")).isTrue();
        assertThat(StudentService.namesMatch("Alice", "Bob Kumar")).isFalse();
        assertThat(StudentService.namesMatch("Al", "Al Pacino")).isFalse(); // too short to prove anything
        assertThat(StudentService.namesMatch("Alice", "")).isFalse();
        assertThat(StudentService.namesMatch("Alice", null)).isFalse();
    }

    @Test
    void collegeEmailsAreRecognisedByTheirEnding() {
        assertThat(service.isCollegeEmail("ram@iitd.ac.in")).isTrue();
        assertThat(service.isCollegeEmail("ram@mit.edu")).isTrue();
        assertThat(service.isCollegeEmail("ram@du.edu.in")).isTrue();
        assertThat(service.isCollegeEmail("ram@gmail.com")).isFalse();
        assertThat(service.isCollegeEmail("ram@ac.in.example.com")).isFalse();
    }

    @Test
    void thePhotoIsReEncodedWithoutItsLocationAndOtherMetadata() throws IOException {
        byte[] original = jpegWithLocation();
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("SECRET-LOCATION");

        byte[] cleaned = service.cleanJpeg(new MockMultipartFile("file", "card.jpg", "image/jpeg", original));

        String text = new String(cleaned, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("SECRET-LOCATION").doesNotContain("Exif");
        assertThat(ImageIO.read(new ByteArrayInputStream(cleaned))).isNotNull();
    }

    @Test
    void bigPhotosAreShrunkAndPngsBecomeJpegs() throws IOException {
        byte[] png = image("png", 4000, 2000);
        byte[] cleaned = service.cleanJpeg(new MockMultipartFile("file", "card.png", "image/png", png));
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(cleaned));
        assertThat(Math.max(read.getWidth(), read.getHeight())).isLessThanOrEqualTo(1568);
        assertThat(cleaned[0] & 0xFF).isEqualTo(0xFF);
        assertThat(cleaned[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void anythingThatIsNotAJpgOrPngIsRefusedWhateverItsName() throws IOException {
        for (byte[] bytes : new byte[][]{"%PDF-1.7 not an image".getBytes(StandardCharsets.US_ASCII),
                image("gif", 50, 50), image("bmp", 50, 50), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2}}) {
            assertThatThrownBy(() -> service.cleanJpeg(new MockMultipartFile("file", "card.jpg", "image/jpeg", bytes)))
                    .isInstanceOf(FieldValidationException.class)
                    .hasMessageContaining("JPG or PNG");
        }
        assertThatThrownBy(() -> service.cleanJpeg(new MockMultipartFile("file", "card.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void photosOverTheSizeLimitAreRefusedBeforeDecoding() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> service.cleanJpeg(new MockMultipartFile("file", "card.jpg", "image/jpeg", big)))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("larger than 5 MB");
    }
}

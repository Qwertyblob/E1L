package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingAttachment;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageSanitizerTest {

    private final ImageSanitizer sanitizer = new ImageSanitizer();

    private static BufferedImage solid(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 80, 200));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static String encode(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static BookingAttachment attach(String name, String type, String data) {
        return new BookingAttachment(name, type, data);
    }

    @Test
    void sanitize_validJpeg_producesServerOwnedJpeg() throws IOException {
        // Deliberately mislabel the type/name; the sanitizer must ignore both and go by the bytes.
        String data = encode(solid(40, 30, BufferedImage.TYPE_INT_RGB), "jpeg");
        List<SanitizedImage> out = sanitizer.sanitize(List.of(attach("whatever.bin", "text/plain", data)));

        assertThat(out).hasSize(1);
        SanitizedImage img = out.get(0);
        assertThat(img.filename()).isEqualTo("inspo-1.jpg");
        assertThat(img.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new ByteArrayInputStream(img.data()))).isNotNull(); // decodes as an image
    }

    @Test
    void sanitize_validPng_producesServerOwnedPng() throws IOException {
        String data = encode(solid(20, 20, BufferedImage.TYPE_INT_ARGB), "png");
        List<SanitizedImage> out = sanitizer.sanitize(List.of(attach("evil.svg", "image/svg+xml", data)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).filename()).isEqualTo("inspo-1.png");
        assertThat(out.get(0).contentType()).isEqualTo("image/png"); // detected from bytes, not the label
    }

    @Test
    void sanitize_nonImageBytes_isSkipped() {
        String data = Base64.getEncoder().encodeToString("this is not an image".getBytes(StandardCharsets.UTF_8));
        assertThat(sanitizer.sanitize(List.of(attach("x.png", "image/png", data)))).isEmpty();
    }

    @Test
    void sanitize_malformedBase64_isSkipped() {
        assertThat(sanitizer.sanitize(List.of(attach("x.jpg", "image/jpeg", "!!! not base64 !!!")))).isEmpty();
    }

    @Test
    void sanitize_oversizedDimensions_isSkipped() throws IOException {
        // 4097px wide exceeds MAX_DIMENSION (4096); rejected on the header read, before the full
        // raster is decoded. Kept 8px tall so the test image itself stays tiny.
        String data = encode(solid(4097, 8, BufferedImage.TYPE_INT_RGB), "png");
        assertThat(sanitizer.sanitize(List.of(attach("wide.png", "image/png", data)))).isEmpty();
    }

    @Test
    void sanitize_stripsBytesAppendedAfterImage() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(solid(16, 16, BufferedImage.TYPE_INT_RGB), "jpeg", baos);
        baos.write("<script>__POLYGLOT_MARKER__</script>".getBytes(StandardCharsets.UTF_8)); // trailing payload
        String data = Base64.getEncoder().encodeToString(baos.toByteArray());

        List<SanitizedImage> out = sanitizer.sanitize(List.of(attach("polyglot.jpg", "image/jpeg", data)));

        assertThat(out).hasSize(1);
        String reencoded = new String(out.get(0).data(), StandardCharsets.ISO_8859_1);
        assertThat(reencoded).doesNotContain("__POLYGLOT_MARKER__"); // re-encode dropped the trailing bytes
        assertThat(ImageIO.read(new ByteArrayInputStream(out.get(0).data()))).isNotNull();
    }

    @Test
    void sanitize_numbersOutputsSequentially_skippingBadOnes() throws IOException {
        String good1 = encode(solid(10, 10, BufferedImage.TYPE_INT_RGB), "jpeg");
        String bad = Base64.getEncoder().encodeToString("nope".getBytes(StandardCharsets.UTF_8));
        String good2 = encode(solid(12, 12, BufferedImage.TYPE_INT_ARGB), "png");

        List<SanitizedImage> out = sanitizer.sanitize(List.of(
                attach("a.jpg", "image/jpeg", good1),
                attach("b.jpg", "image/jpeg", bad),
                attach("c.png", "image/png", good2)));

        // The skipped middle image leaves no gap: outputs are numbered by output position.
        assertThat(out).extracting(SanitizedImage::filename)
                .containsExactly("inspo-1.jpg", "inspo-2.png");
    }

    @Test
    void sanitize_nullOrEmpty_returnsEmpty() {
        assertThat(sanitizer.sanitize(null)).isEmpty();
        assertThat(sanitizer.sanitize(List.of())).isEmpty();
    }
}

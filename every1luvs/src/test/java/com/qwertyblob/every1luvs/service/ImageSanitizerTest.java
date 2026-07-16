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

    private final ImageSanitizer sanitizer = new ImageSanitizer(2);

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

    // A landscape 40x20 source tagged as orientation 6 or 8 (both quarter-turns) must be rotated
    // upright before re-encoding, i.e. the output is portrait 20x40. Orientation 1 (no rotation)
    // is already covered by sanitize_validJpeg above, which keeps its dimensions.

    @Test
    void sanitize_appliesExifOrientation6_rotatesToPortrait() throws IOException {
        String data = Base64.getEncoder().encodeToString(
                jpegWithExifOrientation(solid(40, 20, BufferedImage.TYPE_INT_RGB), 6));
        List<SanitizedImage> out = sanitizer.sanitize(List.of(attach("phone.jpg", "image/jpeg", data)));

        assertThat(out).hasSize(1);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(out.get(0).data()));
        assertThat(result.getWidth()).isEqualTo(20);
        assertThat(result.getHeight()).isEqualTo(40);
    }

    @Test
    void sanitize_appliesExifOrientation8_rotatesToPortrait() throws IOException {
        String data = Base64.getEncoder().encodeToString(
                jpegWithExifOrientation(solid(40, 20, BufferedImage.TYPE_INT_RGB), 8));
        List<SanitizedImage> out = sanitizer.sanitize(List.of(attach("phone.jpg", "image/jpeg", data)));

        assertThat(out).hasSize(1);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(out.get(0).data()));
        assertThat(result.getWidth()).isEqualTo(20);
        assertThat(result.getHeight()).isEqualTo(40);
    }

    // Encode a JPEG, then splice a minimal EXIF APP1 (little-endian, single IFD0 Orientation entry)
    // in right after SOI — the canonical place EXIF lives — so the sanitizer's parser finds it.
    private static byte[] jpegWithExifOrientation(BufferedImage img, int orientation) throws IOException {
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", jpg);
        byte[] jpeg = jpg.toByteArray();

        byte[] tiff = new byte[] {
                0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, // "II", 42, IFD0 offset = 8
                0x01, 0x00,                                     // 1 directory entry
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, // tag 0x0112, type SHORT, count 1
                (byte) orientation, 0x00, 0x00, 0x00,           // value (SHORT in first 2 bytes, LE)
                0x00, 0x00, 0x00, 0x00                          // next IFD offset = 0
        };
        int segLen = 2 + 6 + tiff.length; // length field + "Exif\0\0" + TIFF block

        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write(0xFF); app1.write(0xE1);
        app1.write((segLen >> 8) & 0xFF); app1.write(segLen & 0xFF);
        app1.write('E'); app1.write('x'); app1.write('i'); app1.write('f'); app1.write(0); app1.write(0);
        app1.write(tiff, 0, tiff.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                       // SOI (FF D8)
        out.write(app1.toByteArray());               // our EXIF APP1
        out.write(jpeg, 2, jpeg.length - 2);         // the rest of the original JPEG
        return out.toByteArray();
    }

    // ---- asymmetric-pixel orientation checks ---------------------------------------------------
    // A solid colour can't distinguish a correct quarter-turn from a wrong rotation or a mirror.
    // Paint four distinct quadrants (TL red, TR green, BL blue, BR yellow) and assert where each
    // lands in the output, verifying the exact rotate+flip — this is what catches the orientation-7
    // transverse bug (a wrong transform mapped every pixel outside the canvas → blank/cropped).

    private static final int[] PALETTE = { 0xDC2828, 0x28C828, 0x2828DC, 0xE6D228 }; // RED GREEN BLUE YELLOW
    private static final String[] NAMES = { "RED(TL)", "GREEN(TR)", "BLUE(BL)", "YELLOW(BR)" };

    private static BufferedImage quadrants(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int i = 0; i < 4; i++) {
            g.setColor(new Color(PALETTE[i]));
            int x = (i == 1 || i == 3) ? w / 2 : 0;
            int y = (i >= 2) ? h / 2 : 0;
            g.fillRect(x, y, w / 2, h / 2);
        }
        g.dispose();
        return img;
    }

    private static String cornerName(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF, gr = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int pr = (PALETTE[i] >> 16) & 0xFF, pg = (PALETTE[i] >> 8) & 0xFF, pb = PALETTE[i] & 0xFF;
            long d = (long) (r - pr) * (r - pr) + (long) (gr - pg) * (gr - pg) + (long) (b - pb) * (b - pb);
            if (d < bestD) { bestD = d; best = i; }
        }
        return NAMES[best];
    }

    private BufferedImage sanitizedQuadrants(int orientation) throws IOException {
        String data = Base64.getEncoder().encodeToString(jpegWithExifOrientation(quadrants(40, 20), orientation));
        List<SanitizedImage> out = sanitizer.sanitize(List.of(attach("q.jpg", "image/jpeg", data)));
        assertThat(out).hasSize(1);
        return ImageIO.read(new ByteArrayInputStream(out.get(0).data())); // 20x40 (portrait)
    }

    @Test
    void sanitize_orientation6_rotates90Clockwise_pixelsLandCorrectly() throws IOException {
        BufferedImage r = sanitizedQuadrants(6);
        // 90° CW: TL→top-right, TR→bottom-right, BL→top-left, BR→bottom-left of the portrait output.
        assertThat(cornerName(r, 16, 3)).isEqualTo("RED(TL)");
        assertThat(cornerName(r, 16, 36)).isEqualTo("GREEN(TR)");
        assertThat(cornerName(r, 3, 3)).isEqualTo("BLUE(BL)");
        assertThat(cornerName(r, 3, 36)).isEqualTo("YELLOW(BR)");
    }

    @Test
    void sanitize_orientation7_transverse_pixelsLandCorrectly() throws IOException {
        // Regression guard: the old transform mapped every pixel off-canvas, giving a blank image.
        BufferedImage r = sanitizedQuadrants(7);
        assertThat(cornerName(r, 16, 36)).isEqualTo("RED(TL)");
        assertThat(cornerName(r, 16, 3)).isEqualTo("GREEN(TR)");
        assertThat(cornerName(r, 3, 36)).isEqualTo("BLUE(BL)");
        assertThat(cornerName(r, 3, 3)).isEqualTo("YELLOW(BR)");
    }
}

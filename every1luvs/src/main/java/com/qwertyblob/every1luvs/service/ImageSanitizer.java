package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Turns untrusted client {@link BookingAttachment}s into server-owned {@link SanitizedImage}s.
 *
 * <p>Client attachments arrive as base64 with an attacker-chosen filename and contentType; forwarding
 * those bytes to the admin inbox as-is would let a caller deliver a polyglot/malware file labelled
 * {@code image/jpeg}. For each attachment this:
 * <ol>
 *   <li>base64-decodes the bytes;</li>
 *   <li>detects the real format from the bytes (not the client label) and accepts JPEG/PNG only —
 *       Java's stock {@code ImageIO} has no WebP/HEIC decoder;</li>
 *   <li>reads the pixel dimensions from the header <em>before</em> decoding the raster and rejects
 *       anything past the dimension/pixel caps (a decompression-bomb guard — a few KB that would
 *       inflate to a multi-GB raster never gets decoded);</li>
 *   <li>applies the JPEG's EXIF orientation to the pixels (so phone photos aren't re-encoded
 *       sideways once the tag is dropped), then <em>re-encodes</em> from the pixels, which is what
 *       actually strips EXIF/ICC metadata and any bytes appended after the image's end marker.</li>
 * </ol>
 * Anything that fails any step is skipped and logged, never thrown — attachments are a best-effort
 * courtesy to the salon and must not fail a booking that has already succeeded.
 *
 * <p><b>Memory bound.</b> Decoding a raster costs ~4 bytes/pixel and JPEG re-encoding allocates a
 * second RGB raster, so one image in flight is roughly {@code 8 * pixels} bytes of heap. The pixel
 * cap bounds a single image (~{@value #MAX_PIXELS} px ⇒ ~128 MB worst case) and a {@link Semaphore}
 * bounds how many sanitizations run at once, so a burst of concurrent public bookings can't exhaust
 * the heap. The concurrency is tunable via {@code app.attachments.max-concurrent-sanitize} to match
 * the production heap budget.
 */
@Service
public class ImageSanitizer {
    private static final Logger logger = LoggerFactory.getLogger(ImageSanitizer.class);

    // Per-side and total-area caps. 4096/side accommodates high-res phone photos; the pixel cap is
    // set BELOW 4096² (16.78M) so it actually bites for near-square images and pins the decoded
    // raster to ~64 MB (16M px * 4 bytes) — it is not redundant with the per-side check.
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 16_000_000L;
    private static final float JPEG_QUALITY = 0.85f;
    // If every sanitize slot is busy, wait briefly rather than dropping the image outright; under a
    // genuine burst the wait lapses and the (best-effort) attachment is skipped to protect the heap.
    private static final long ACQUIRE_TIMEOUT_MS = 5_000L;

    private final Semaphore slots;
    private final int maxConcurrent;

    public ImageSanitizer(
            @Value("${app.attachments.max-concurrent-sanitize:2}") int maxConcurrentSanitize) {
        this.maxConcurrent = Math.max(1, maxConcurrentSanitize);
        this.slots = new Semaphore(this.maxConcurrent);
    }

    /**
     * Sanitize every attachment, dropping any that can't be decoded as a bounded JPEG/PNG. The
     * returned list is server-owned and safe to email; it may be shorter than the input (or empty).
     * A single permit is held for the whole call — images within one booking are processed in
     * series, so at most {@code maxConcurrent} rasters exist across all in-flight bookings.
     */
    public List<SanitizedImage> sanitize(List<BookingAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        boolean acquired;
        try {
            acquired = slots.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        if (!acquired) {
            logger.warn("Skipping inspo image sanitization: all {} slot(s) busy.", maxConcurrent);
            return List.of();
        }
        try {
            List<SanitizedImage> clean = new ArrayList<>();
            for (BookingAttachment attachment : attachments) {
                if (attachment == null || attachment.data() == null || attachment.data().isBlank()) {
                    continue;
                }
                // Number by output position so a skipped image doesn't leave a gap in the filenames.
                SanitizedImage sanitized = sanitizeOne(attachment.data(), clean.size());
                if (sanitized != null) {
                    clean.add(sanitized);
                }
            }
            return clean;
        } finally {
            slots.release();
        }
    }

    private SanitizedImage sanitizeOne(String base64, int index) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            logger.warn("Skipping inspo image with malformed base64.");
            return null;
        }

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                logger.warn("Skipping inspo image: unrecognised (non-image) bytes.");
                return null;
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!format.equals("JPEG") && !format.equals("PNG")) {
                    logger.warn("Skipping inspo image: unsupported format {}.", format);
                    return null;
                }
                reader.setInput(iis, true, true);
                // Dimensions come from the header, BEFORE reading the raster — this is the bomb guard.
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    logger.warn("Skipping inspo image: dimensions {}x{} exceed limits.", width, height);
                    return null;
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    return null;
                }
                // Re-encoding drops the EXIF orientation tag, so bake it into the pixels first —
                // otherwise a phone photo (commonly orientation 6) would arrive sideways.
                if (format.equals("JPEG")) {
                    image = applyOrientation(image, readJpegOrientation(raw));
                }
                return reencode(image, format, index);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Skipping inspo image that could not be decoded: {}", e.getMessage());
            return null;
        }
    }

    private SanitizedImage reencode(BufferedImage image, String format, int index) throws IOException {
        if (format.equals("PNG")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", baos)) {
                return null;
            }
            return new SanitizedImage("inspo-" + (index + 1) + ".png", "image/png", baos.toByteArray());
        }
        // JPEG has no alpha channel: draw onto an opaque RGB canvas, then write at a fixed quality.
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return new SanitizedImage("inspo-" + (index + 1) + ".jpg", "image/jpeg", baos.toByteArray());
    }

    /**
     * Rotate/flip the decoded pixels to match the EXIF orientation (1–8) so the re-encoded image
     * looks upright once the tag is gone. Orientations 5–8 swap width and height.
     */
    private BufferedImage applyOrientation(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.scale(-1.0, 1.0); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1.0, -1.0); t.translate(0, -h); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1.0, -1.0); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1.0, 1.0); t.translate(-h, 0); t.rotate(Math.PI / 2); t.scale(1.0, -1.0); t.translate(0, -w); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { return image; }
        }
        boolean swap = orientation >= 5;
        int type = image.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB : image.getType();
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, type);
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * Read the EXIF orientation (1–8) from a JPEG's APP1 segment, or 1 if absent/unparseable. A tiny
     * hand-rolled parser (no dependency): find the {@code Exif\0\0} APP1, read the TIFF header's byte
     * order, then scan IFD0 for the Orientation tag {@code 0x0112}.
     */
    private int readJpegOrientation(byte[] jpeg) {
        try {
            int offset = 2; // skip SOI (FF D8)
            while (offset + 4 <= jpeg.length) {
                if ((jpeg[offset] & 0xFF) != 0xFF) {
                    break;
                }
                int marker = jpeg[offset + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    break; // start-of-scan / end-of-image — no metadata beyond here
                }
                int segLen = ((jpeg[offset + 2] & 0xFF) << 8) | (jpeg[offset + 3] & 0xFF);
                int segStart = offset + 2;
                if (marker == 0xE1 && segLen >= 14
                        && jpeg[offset + 4] == 'E' && jpeg[offset + 5] == 'x'
                        && jpeg[offset + 6] == 'i' && jpeg[offset + 7] == 'f'
                        && jpeg[offset + 8] == 0 && jpeg[offset + 9] == 0) {
                    return parseTiffOrientation(jpeg, offset + 10, Math.min(jpeg.length, segStart + segLen));
                }
                offset = segStart + segLen;
            }
        } catch (RuntimeException e) {
            // Malformed metadata — treat as no orientation.
        }
        return 1;
    }

    private int parseTiffOrientation(byte[] d, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little;
        int b0 = d[tiff] & 0xFF;
        int b1 = d[tiff + 1] & 0xFF;
        if (b0 == 0x49 && b1 == 0x49) {
            little = true;
        } else if (b0 == 0x4D && b1 == 0x4D) {
            little = false;
        } else {
            return 1;
        }
        int ifd = tiff + readInt(d, tiff + 4, little);
        if (ifd + 2 > end) {
            return 1;
        }
        int entries = readShort(d, ifd, little);
        int p = ifd + 2;
        for (int i = 0; i < entries && p + 12 <= end; i++, p += 12) {
            if (readShort(d, p, little) == 0x0112) {
                int value = readShort(d, p + 8, little); // SHORT value sits in the first 2 value bytes
                return (value >= 1 && value <= 8) ? value : 1;
            }
        }
        return 1;
    }

    private int readShort(byte[] d, int i, boolean little) {
        int a = d[i] & 0xFF;
        int b = d[i + 1] & 0xFF;
        return little ? (b << 8) | a : (a << 8) | b;
    }

    private int readInt(byte[] d, int i, boolean little) {
        int a = d[i] & 0xFF;
        int b = d[i + 1] & 0xFF;
        int c = d[i + 2] & 0xFF;
        int e = d[i + 3] & 0xFF;
        return little ? (e << 24) | (c << 16) | (b << 8) | a : (a << 24) | (b << 16) | (c << 8) | e;
    }
}

package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

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
 *   <li>decodes and <em>re-encodes</em> from the pixels, which is what actually strips EXIF/ICC
 *       metadata and any bytes appended after the image's end marker.</li>
 * </ol>
 * Anything that fails any step is skipped and logged, never thrown — attachments are a best-effort
 * courtesy to the salon and must not fail a booking that has already succeeded.
 */
@Service
public class ImageSanitizer {
    private static final Logger logger = LoggerFactory.getLogger(ImageSanitizer.class);

    // Hard dimension caps. 4096px per side comfortably covers phone photos; the pixel cap (~24MP)
    // bounds the decoded raster (~96MB at 4 bytes/px) so a crafted small file can't exhaust memory.
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 24_000_000L;
    private static final float JPEG_QUALITY = 0.85f;

    /**
     * Sanitize every attachment, dropping any that can't be decoded as a bounded JPEG/PNG. The
     * returned list is server-owned and safe to email; it may be shorter than the input (or empty).
     */
    public List<SanitizedImage> sanitize(List<BookingAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
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
}

package com.qwertyblob.every1luvs.dto;

/**
 * A single inspo image a customer attaches to their booking. These are transported with the
 * booking request purely so the backend can forward them to the salon inbox as email
 * attachments — they are never persisted (no DB column, not echoed in {@code BookingResponse}).
 *
 * <p><b>Untrusted client input.</b> {@code filename} and {@code contentType} are attacker-controlled
 * and are NOT used for the emailed attachment: {@code service.ImageSanitizer} detects the real format
 * from the bytes, re-encodes them, and produces a server-owned {@code service.SanitizedImage} with a
 * generated name and detected type. Nothing here reaches the inbox verbatim.
 *
 * @param filename    original file name (client-supplied, untrusted)
 * @param contentType MIME type (client-supplied, untrusted; the real format is detected server-side)
 * @param data        Base64-encoded image bytes, with any {@code data:} URL prefix stripped
 */
public record BookingAttachment(
        String filename,
        String contentType,
        String data
) {
}

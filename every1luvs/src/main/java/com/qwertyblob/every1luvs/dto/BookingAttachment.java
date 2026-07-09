package com.qwertyblob.every1luvs.dto;

/**
 * A single inspo image a customer attaches to their booking. These are transported with the
 * booking request purely so the backend can forward them to the salon inbox as email
 * attachments — they are never persisted (no DB column, not echoed in {@code BookingResponse}).
 *
 * @param filename    original file name, used only as the attachment name in the email
 * @param contentType MIME type (must be an {@code image/*} type; validated server-side)
 * @param data        Base64-encoded image bytes, with any {@code data:} URL prefix stripped
 */
public record BookingAttachment(
        String filename,
        String contentType,
        String data
) {
}

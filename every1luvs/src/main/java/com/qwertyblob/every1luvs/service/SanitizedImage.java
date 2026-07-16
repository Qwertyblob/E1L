package com.qwertyblob.every1luvs.service;

/**
 * A booking inspo image the server has fully re-encoded and now vouches for. Unlike the
 * client-supplied {@link com.qwertyblob.every1luvs.dto.BookingAttachment}, every field here is
 * server-owned: {@code data} are freshly re-encoded bytes (no original metadata or trailing
 * polyglot payload survives), {@code contentType} is the format detected from the bytes, and
 * {@code filename} is generated. Only this type may be handed to the admin mail notification, so
 * attacker-controlled bytes/labels can never reach the salon inbox.
 *
 * @param filename    generated attachment name (e.g. {@code inspo-1.jpg})
 * @param contentType detected media type ({@code image/jpeg} or {@code image/png})
 * @param data        re-encoded image bytes
 */
public record SanitizedImage(String filename, String contentType, byte[] data) {
}

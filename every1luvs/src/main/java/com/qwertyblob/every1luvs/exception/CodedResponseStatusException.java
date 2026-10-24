package com.qwertyblob.every1luvs.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * A ResponseStatusException carrying a machine-readable {@code code} that {@link ApiExceptionHandler}
 * passes through in the error body, so the SPA can tell e.g. "prices changed" apart from other 409s
 * without matching on message text.
 */
public class CodedResponseStatusException extends ResponseStatusException {

    /** The published catalog changed since the customer reviewed their quote. */
    public static final String CATALOG_VERSION_MISMATCH = "CATALOG_VERSION_MISMATCH";
    /** The picked time can no longer take this booking (capacity / concurrent seat change). */
    public static final String SLOT_UNAVAILABLE = "SLOT_UNAVAILABLE";

    private final String code;

    public CodedResponseStatusException(HttpStatusCode status, String reason, String code) {
        super(status, reason);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

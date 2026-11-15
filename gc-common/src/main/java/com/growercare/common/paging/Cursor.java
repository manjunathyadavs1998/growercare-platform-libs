package com.growercare.common.paging;

import com.growercare.common.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque keyset cursor: base64url({@code "v1:" + lastId}). Clients must treat it as an opaque token; the version
 * prefix lets the format evolve. Anything that does not decode to a positive id is a 400 on field {@code cursor}.
 */
public final class Cursor {

    private static final String PREFIX = "v1:";

    private Cursor() { }

    public static String encode(long lastId) {
        if (lastId <= 0) {
            throw new IllegalArgumentException("Cursor ids are positive");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString((PREFIX + lastId).getBytes(StandardCharsets.US_ASCII));
    }

    public static long decode(String cursor) {
        try {
            var text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (!text.startsWith(PREFIX)) {
                throw invalid();
            }
            long id = Long.parseLong(text.substring(PREFIX.length()));
            if (id <= 0) {
                throw invalid();
            }
            return id;
        } catch (IllegalArgumentException e) {   // includes NumberFormatException and bad base64
            throw invalid();
        }
    }

    private static ValidationException invalid() {
        return ValidationException.of("cursor", "is not a valid cursor");
    }
}

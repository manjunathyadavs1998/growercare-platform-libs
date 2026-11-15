package com.growercare.common.error;

import java.util.Map;

/** 403: the caller lacks the function-level permission (object-level scope failures use 404). */
public final class PermissionDeniedException extends GcException {

    public PermissionDeniedException(String permission) {
        super(ErrorCode.PERMISSION_DENIED, "Missing permission " + permission, Map.of("permission", permission), null);
    }
}

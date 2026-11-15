package com.growercare.common.error;

import java.util.Map;

/** 501: the request targets data outside the hot window and the archive (Snowflake) adapter is not enabled. */
public final class ArchiveNotEnabledException extends GcException {

    public ArchiveNotEnabledException() {
        super(ErrorCode.ARCHIVE_NOT_ENABLED, "Data older than the hot window is not available yet", Map.of(), null);
    }
}

package com.growercare.shard;

import java.time.ZoneId;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A country as known to the ID scheme (doc 04, ID-5): 6-bit index for {@code case_id}, two-digit public
 * slot for {@code case_no}, region, and the reference time zone used for the {@code case_no} day number.
 */
public record Country(String code, int countryIx, int caseNoCc, String regionCode, ZoneId zone) {

    private static final Pattern ISO2 = Pattern.compile("^[A-Z]{2}$");

    public Country {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(regionCode, "regionCode");
        Objects.requireNonNull(zone, "zone");
        if (!ISO2.matcher(code).matches()) {
            throw new IllegalArgumentException("Country code must be ISO 3166-1 alpha-2: " + code);
        }
        if (countryIx < 0 || countryIx > IdCodec.MAX_COUNTRY_IX) {
            throw new IllegalArgumentException("countryIx out of range 0..63: " + countryIx);
        }
        if (caseNoCc < 10 || caseNoCc > 99) {
            throw new IllegalArgumentException("caseNoCc out of range 10..99: " + caseNoCc);
        }
    }
}

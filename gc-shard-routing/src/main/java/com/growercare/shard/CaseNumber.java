package com.growercare.shard;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Public 15-digit case number {@code CC DDDD HH SSSSSS K} (doc 04 §4), e.g. {@code 71-0318-27-482139-6}.
 * Parsing accepts the grouped (dashes/spaces) or compact form and verifies the Damm check digit, so typos
 * are rejected before any routing or database call.
 */
public record CaseNumber(String digits) {

    private static final Pattern FORMAT = Pattern.compile("^[1-9]\\d{14}$");

    public CaseNumber {
        if (digits == null || !FORMAT.matcher(digits).matches() || !Damm.isValid(digits)) {
            throw new IllegalArgumentException("Invalid case number");
        }
    }

    /** Lenient parse for user input; empty when the format or check digit is wrong. */
    public static Optional<CaseNumber> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        var compact = input.replaceAll("[\\s-]", "");
        return FORMAT.matcher(compact).matches() && Damm.isValid(compact)
                ? Optional.of(new CaseNumber(compact))
                : Optional.empty();
    }

    public int caseNoCc() { return Integer.parseInt(digits.substring(0, 2)); }

    public int dayNumber() { return Integer.parseInt(digits.substring(2, 6)); }

    public int shardNo() { return Integer.parseInt(digits.substring(6, 8)); }

    public int counter() { return Integer.parseInt(digits.substring(8, 14)); }

    /** Display form {@code CC-DDDD-HH-SSSSSS-K}. */
    public String formatted() {
        return "%s-%s-%s-%s-%s".formatted(digits.substring(0, 2), digits.substring(2, 6), digits.substring(6, 8),
                digits.substring(8, 14), digits.substring(14));
    }

    @Override
    public String toString() { return formatted(); }
}

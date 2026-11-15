package com.growercare.shard;

/**
 * Damm check digit (detects all single-digit errors and adjacent transpositions).
 * Must agree with {@code ref.damm_check_digit(text)} in SQL; a parity test checks both.
 */
public final class Damm {

    private static final int[][] TABLE = {
            {0, 3, 1, 7, 5, 9, 8, 6, 4, 2},
            {7, 0, 9, 2, 1, 5, 4, 8, 6, 3},
            {4, 2, 0, 6, 8, 7, 1, 3, 5, 9},
            {1, 7, 5, 0, 9, 8, 3, 4, 2, 6},
            {6, 1, 2, 3, 0, 4, 5, 9, 7, 8},
            {3, 6, 7, 4, 2, 0, 9, 5, 8, 1},
            {5, 8, 6, 9, 7, 2, 0, 1, 3, 4},
            {8, 9, 4, 5, 3, 6, 2, 0, 1, 7},
            {9, 4, 3, 8, 6, 1, 7, 2, 0, 5},
            {2, 5, 8, 1, 4, 3, 6, 7, 9, 0}
    };

    private Damm() { }

    public static int checkDigit(CharSequence digits) {
        requireDigits(digits);
        return digits.chars()
                .map(c -> c - '0')
                .reduce(0, (interim, digit) -> TABLE[interim][digit]);
    }

    /** True when the last digit is the correct Damm check digit for the preceding digits. */
    public static boolean isValid(CharSequence digitsWithCheck) {
        requireDigits(digitsWithCheck);
        return digitsWithCheck.chars().map(c -> c - '0').reduce(0, (interim, digit) -> TABLE[interim][digit]) == 0;
    }

    private static void requireDigits(CharSequence s) {
        if (s == null || s.isEmpty() || !s.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Digits only");
        }
    }
}

package com.growercare.security;

/** What a role may see of personal data by default (ACC-3). */
public enum PiiClearance {
    NONE, MASKED, FULL
}

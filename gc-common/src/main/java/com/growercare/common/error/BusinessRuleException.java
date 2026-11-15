package com.growercare.common.error;

import java.util.Map;

/** 422: request is well-formed but violates a domain rule (invalid transition, SoD conflict, …). */
public final class BusinessRuleException extends GcException {

    private final String rule;

    public BusinessRuleException(String rule, String message) {
        this(rule, message, null);
    }

    public BusinessRuleException(String rule, String message, Throwable cause) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message, Map.of("rule", rule), cause);
        this.rule = rule;
    }

    /** Machine-readable rule identifier, e.g. {@code case.transition.not-allowed}. */
    public String rule() { return rule; }
}

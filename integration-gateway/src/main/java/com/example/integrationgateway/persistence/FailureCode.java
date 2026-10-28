package com.example.integrationgateway.persistence;

/** Failure classifications persisted with non-successful fulfillment outcomes. */
public enum FailureCode {
    OUTBOUND_RATE_LIMITED,
    CIRCUIT_OPEN,
    PARTNER_RATE_LIMITED,
    PARTNER_FAILURE,
    UNKNOWN_OUTCOME;

    /** Returns the persisted string representation of this failure code. */
    public String code() {
        return name();
    }

    /** Returns whether a failure code is eligible for same-key reclamation. */
    public static boolean isRetryable(String code) {
        return OUTBOUND_RATE_LIMITED.code().equals(code)
                || CIRCUIT_OPEN.code().equals(code)
                || PARTNER_RATE_LIMITED.code().equals(code);
    }
}

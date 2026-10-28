package com.example.integrationgateway.persistence;

public enum FulfillmentStatus {
    /** Gateway owns the submission and has not persisted its outcome yet. */
    PROCESSING,
    /** Partner completion was confirmed directly or through reconciliation. */
    COMPLETED,
    /** A partner failure classified as final by gateway policy; this state is not automatically reclaimed. */
    FAILED,
    /** A selected transient dependency failure may be reclaimed by a later same-key request. */
    RETRYABLE_FAILURE,
    /** Partner execution could not be confirmed or ruled out. */
    UNKNOWN
}

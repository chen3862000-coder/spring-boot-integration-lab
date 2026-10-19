package com.example.integrationgateway.client;

import org.slf4j.MDC;

public final class Correlation {
    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    private Correlation() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? "missing-correlation-id" : value;
    }
}

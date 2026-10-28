package com.example.integrationgateway.client;

import org.slf4j.MDC;

public final class Correlation {
    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    private Correlation() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}

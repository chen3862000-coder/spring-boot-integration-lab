package com.example.mockpartner.fault;

public enum FaultScenario {
    NORMAL,
    SLOW_RESPONSE,
    HTTP_500,
    HTTP_429,
    TIMEOUT_AFTER_COMMIT,
    SERVICE_UNAVAILABLE;

    public static FaultScenario from(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        return FaultScenario.valueOf(value.trim().toUpperCase());
    }
}

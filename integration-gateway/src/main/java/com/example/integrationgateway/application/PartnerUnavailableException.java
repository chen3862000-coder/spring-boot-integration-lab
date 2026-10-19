package com.example.integrationgateway.application;

public class PartnerUnavailableException extends RuntimeException {
    public PartnerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

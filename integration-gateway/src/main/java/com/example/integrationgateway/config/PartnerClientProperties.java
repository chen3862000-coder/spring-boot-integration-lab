package com.example.integrationgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "partner")
public record PartnerClientProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {
}

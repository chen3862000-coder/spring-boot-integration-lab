package com.example.integrationgateway.client;

import com.example.integrationgateway.application.PartnerUnavailableException;
import com.example.integrationgateway.config.PartnerClientProperties;
import com.example.integrationgateway.persistence.FulfillmentRecord;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;

/** Translates gateway fulfillment operations to the partner HTTP protocol and applies outbound resilience policies. */
@Component
public class PartnerClient {

    private static final Logger log = LoggerFactory.getLogger(PartnerClient.class);

    private final RestClient restClient;
    private final boolean faultInjectionEnabled;
    private final io.github.resilience4j.retry.Retry retry;
    private final io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker;
    private final io.github.resilience4j.ratelimiter.RateLimiter rateLimiter;

    public PartnerClient(
            PartnerClientProperties properties,
            @Value("${demo.fault-injection-enabled:false}") boolean faultInjectionEnabled,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RateLimiterRegistry rateLimiterRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.faultInjectionEnabled = faultInjectionEnabled;
        this.retry = retryRegistry.retry("partnerApi");
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("partnerApi");
        this.rateLimiter = rateLimiterRegistry.rateLimiter("partnerApi");
    }

    /**
     * Sends a partner write using the idempotency key and configured retry, circuit-breaker, and rate-limit policies.
     *
     * <p>Transport failures are reported as ambiguous because the partner may have committed the write. HTTP 429 and
     * 5xx responses are retry candidates; other partner error responses are non-retryable. The fault scenario is only
     * forwarded when demo fault injection is enabled.
     */
    public PartnerResponse createFulfillment(FulfillmentRecord record, String idempotencyKey, String partnerFault) {
        try {
            return circuitBreaker.executeSupplier(() -> retry.executeSupplier(
                    () -> rateLimiter.executeSupplier(() -> sendFulfillment(record, idempotencyKey, partnerFault))));
        } catch (CallNotPermittedException ex) {
            throw new PartnerUnavailableException("Partner circuit is open", ex);
        }
    }

    private PartnerResponse sendFulfillment(FulfillmentRecord record, String idempotencyKey, String partnerFault) {
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/partner/fulfillments")
                    .header("Idempotency-Key", idempotencyKey);
            String correlationId = Correlation.current();
            if (correlationId != null) {
                requestSpec.header(Correlation.HEADER, correlationId);
            }
            if (faultInjectionEnabled && partnerFault != null && !partnerFault.isBlank()) {
                requestSpec.header("X-Partner-Fault", partnerFault);
            }
            return requestSpec.body(new PartnerRequest(record.requestId(), record.orderId(), record.sku(), record.quantity()))
                    .retrieve()
                    .body(PartnerResponse.class);
        } catch (ResourceAccessException ex) {
            log.warn("Partner HTTP transport failure for requestId={}", record.requestId(), ex);
            throw new AmbiguousPartnerException("Partner write timed out or connection failed; execution may have reached partner", ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 429 || ex.getStatusCode().is5xxServerError()) {
                throw ex;
            }
            throw new NonRetryablePartnerException("Partner returned non-retryable status " + ex.getStatusCode().value(), ex);
        }
    }

    /**
     * Looks up a partner operation, typically to reconcile an ambiguous write.
     *
     * <p>This lookup does not use the POST operation's retry, circuit-breaker, or rate-limiter decorators.
     */
    public PartnerResponse getFulfillment(String partnerRequestId, String idempotencyKey) {
        RestClient.RequestHeadersSpec<?> requestSpec = restClient.get()
                .uri("/partner/fulfillments/{partnerRequestId}", partnerRequestId)
                .header("Idempotency-Key", idempotencyKey);
        String correlationId = Correlation.current();
        if (correlationId != null) {
            requestSpec = requestSpec.header(Correlation.HEADER, correlationId);
        }
        return requestSpec.retrieve().body(PartnerResponse.class);
    }

    public record PartnerRequest(String partnerRequestId, String orderId, String sku, int quantity) {
    }

    public record PartnerResponse(String partnerRequestId, String orderId, String status, String partnerReference) {
    }

    public static class AmbiguousPartnerException extends RuntimeException {
        public AmbiguousPartnerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class NonRetryablePartnerException extends RuntimeException {
        public NonRetryablePartnerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

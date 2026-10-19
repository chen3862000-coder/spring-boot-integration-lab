package com.example.integrationgateway.client;

import com.example.integrationgateway.application.PartnerUnavailableException;
import com.example.integrationgateway.config.PartnerClientProperties;
import com.example.integrationgateway.persistence.FulfillmentRecord;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;

@Component
public class PartnerClient {

    private final RestClient restClient;

    public PartnerClient(PartnerClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Retry(name = "partnerApi")
    @CircuitBreaker(name = "partnerApi", fallbackMethod = "partnerUnavailable")
    @RateLimiter(name = "partnerApi")
    public PartnerResponse createFulfillment(FulfillmentRecord record, String idempotencyKey, String partnerFault) {
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/partner/fulfillments")
                    .header("Idempotency-Key", idempotencyKey)
                    .header("X-Correlation-ID", Correlation.current());
            if (partnerFault != null && !partnerFault.isBlank()) {
                requestSpec.header("X-Partner-Fault", partnerFault);
            }
            return requestSpec.body(new PartnerRequest(record.requestId(), record.orderId(), record.sku(), record.quantity()))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new HttpClientErrorException(response.getStatusCode(), "Non-retryable partner response");
                    })
                    .body(PartnerResponse.class);
        } catch (ResourceAccessException ex) {
            throw new AmbiguousPartnerException("Partner write timed out or connection failed; execution may have reached partner", ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 429 || ex.getStatusCode().is5xxServerError()) {
                throw ex;
            }
            throw new NonRetryablePartnerException("Partner returned non-retryable status " + ex.getStatusCode().value(), ex);
        }
    }

    public PartnerResponse getFulfillment(String partnerRequestId, String idempotencyKey) {
        return restClient.get()
                .uri("/partner/fulfillments/{partnerRequestId}", partnerRequestId)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Correlation-ID", Correlation.current())
                .retrieve()
                .body(PartnerResponse.class);
    }

    @SuppressWarnings("unused")
    PartnerResponse partnerUnavailable(FulfillmentRecord record, String idempotencyKey, String partnerFault, Throwable throwable) {
        throw new PartnerUnavailableException("Partner circuit is open or dependency is unavailable", throwable);
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

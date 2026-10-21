package com.example.integrationgateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FulfillmentResilienceTests extends GatewayIntegrationTestSupport {

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @Autowired
    RateLimiterRegistry rateLimiters;

    @Test
    void serverFailureRetriesButBadRequestDoesNot() {
        PARTNER.stubFor(post("/partner/fulfillments")
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willSetStateTo("second")
                .willReturn(aResponse().withStatus(500).withFixedDelay(10)));
        PARTNER.stubFor(post("/partner/fulfillments")
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(okJson("""
                        {"partnerRequestId":"REQ-RETRY","orderId":"ORDER-R","status":"COMPLETED","partnerReference":"PARTNER-R"}
                        """)));

        assertThat(submit("retry-" + UUID.randomUUID(), body("ORDER-R")).status()).isEqualTo(HttpStatus.CREATED);
        PARTNER.verify(2, postRequestedFor(urlEqualTo("/partner/fulfillments")));

        PARTNER.resetAll();
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(badRequest()));
        assertThat(submit("bad-request-" + UUID.randomUUID(), body("ORDER-BAD")).status())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        PARTNER.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void timeoutAfterCommitReconcilesWithoutDuplicateWrite() {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(aResponse().withFixedDelay(6_000).withBody("""
                {"partnerRequestId":"REQ-T","orderId":"ORDER-T","status":"COMPLETED","partnerReference":"PARTNER-T"}
                """).withHeader("Content-Type", "application/json")));
        PARTNER.stubFor(get(urlPathMatching("/partner/fulfillments/.*")).willReturn(okJson("""
                {"partnerRequestId":"REQ-T","orderId":"ORDER-T","status":"COMPLETED","partnerReference":"PARTNER-T"}
                """)));

        SimpleResponse response = submit("timeout-" + UUID.randomUUID(), body("ORDER-T"));

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.body().get("status")).isEqualTo("COMPLETED");
        PARTNER.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void partnerRateLimitCanBeRetriedLaterWithSameIdempotencyKey() {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(aResponse().withStatus(429)));
        String key = "partner-429-" + UUID.randomUUID();

        SimpleResponse rejected = submit(key, body("ORDER-429"));

        assertThat(rejected.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rejected.body().get("failureCode")).isEqualTo("PARTNER_RATE_LIMITED");
        PARTNER.verify(3, postRequestedFor(urlEqualTo("/partner/fulfillments")));

        PARTNER.resetAll();
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-RECOVERED","orderId":"ORDER-429","status":"COMPLETED","partnerReference":"PARTNER-RECOVERED"}
                """)));
        assertThat(submit(key, body("ORDER-429")).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(submit(key, body("ORDER-429")).status()).isEqualTo(HttpStatus.OK);
        PARTNER.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void exhaustedServerFailuresReturnBadGateway() {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(serverError()));

        SimpleResponse response = submit("exhausted-" + UUID.randomUUID(), body("ORDER-EXHAUSTED"));

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.body().get("failureCode")).isEqualTo("PARTNER_FAILURE");
        PARTNER.verify(3, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void outboundLimiterRejectsAndAllowsSameKeyRetryAfterRefresh() {
        rateLimiters.rateLimiter("partnerApi").changeLimitForPeriod(1);
        rateLimiters.rateLimiter("partnerApi").drainPermissions();
        waitForOneSecond();
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-LIMIT","orderId":"ORDER-LIMIT","status":"COMPLETED","partnerReference":"PARTNER-LIMIT"}
                """)));

        assertThat(submit("consume-" + UUID.randomUUID(), body("ORDER-CONSUME")).status())
                .isEqualTo(HttpStatus.CREATED);
        String retryKey = "limited-" + UUID.randomUUID();
        SimpleResponse limited = submit(retryKey, body("ORDER-LIMIT"));
        assertThat(limited.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.body().get("failureCode")).isEqualTo("OUTBOUND_RATE_LIMITED");

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(submit(retryKey, body("ORDER-LIMIT")).status()).isEqualTo(HttpStatus.CREATED));
    }

    @Test
    void eachRetryAttemptConsumesAnOutboundRateLimitPermit() {
        rateLimiters.rateLimiter("partnerApi").changeLimitForPeriod(1);
        rateLimiters.rateLimiter("partnerApi").drainPermissions();
        waitForOneSecond();
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(serverError()));

        SimpleResponse response = submit("limited-retry-" + UUID.randomUUID(), body("ORDER-RETRY-LIMITED"));

        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.body().get("failureCode")).isEqualTo("OUTBOUND_RATE_LIMITED");
        PARTNER.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void openCircuitReturns503AndLaterSameKeyCanRecover() {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(serverError()));
        primeCircuitUntilOpen();
        String key = "circuit-retry-" + UUID.randomUUID();

        SimpleResponse rejected = submit(key, body("ORDER-CB-RETRY"));
        assertThat(rejected.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rejected.body().get("failureCode")).isEqualTo("CIRCUIT_OPEN");
        assertThat(rejected.body().get("status")).isEqualTo("RETRYABLE_FAILURE");

        PARTNER.resetAll();
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-CB-RETRY","orderId":"ORDER-CB-RETRY","status":"COMPLETED","partnerReference":"PARTNER-CB-RETRY"}
                """)));
        waitForHalfOpen();
        assertThat(submit(key, body("ORDER-CB-RETRY")).status()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void circuitTransitionsThroughOpenHalfOpenAndClosed() {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(serverError()));
        primeCircuitUntilOpen();
        assertThat(circuitBreakers.circuitBreaker("partnerApi").getState().name()).isEqualTo("OPEN");

        PARTNER.resetAll();
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-RECOVER","orderId":"ORDER-RECOVER","status":"COMPLETED","partnerReference":"PARTNER-RECOVER"}
                """)));
        waitForHalfOpen();
        assertThat(submit("recover-" + UUID.randomUUID(), body("ORDER-RECOVER")).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(circuitBreakers.circuitBreaker("partnerApi").getState().name()).isEqualTo("CLOSED");
    }

    @Test
    void missingPartnerStatusLeavesAmbiguousWriteUnknown() {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(aResponse().withFixedDelay(6_000).withBody("{}")
                .withHeader("Content-Type", "application/json")));
        PARTNER.stubFor(get(urlPathMatching("/partner/fulfillments/.*"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.notFound()));

        SimpleResponse response = submit("timeout-unknown-" + UUID.randomUUID(), body("ORDER-TIMEOUT-UNKNOWN"));

        assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.body().get("status")).isEqualTo("UNKNOWN");
    }

    private void primeCircuitUntilOpen() {
        for (int i = 0; i < 10 && !circuitBreakers.circuitBreaker("partnerApi").getState().name().equals("OPEN"); i++) {
            submit("cb-prime-" + i + "-" + UUID.randomUUID(), body("ORDER-CB-PRIME-" + i));
        }
        assertThat(circuitBreakers.circuitBreaker("partnerApi").getState().name()).isEqualTo("OPEN");
    }

    private void waitForHalfOpen() {
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(circuitBreakers.circuitBreaker("partnerApi").getState().name()).isEqualTo("HALF_OPEN"));
    }

    private void waitForOneSecond() {
        try {
            Thread.sleep(1_100);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for rate limiter refresh", ex);
        }
    }
}

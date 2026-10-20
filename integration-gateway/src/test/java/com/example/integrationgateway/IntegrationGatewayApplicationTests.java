package com.example.integrationgateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationGatewayApplicationTests {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    static WireMockServer partner = new WireMockServer(0);

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    RateLimiterRegistry rateLimiterRegistry;

    HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startPartner() {
        partner.start();
    }

    @AfterAll
    static void stopPartner() {
        partner.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("partner.base-url", () -> partner.baseUrl());
        registry.add("partner.read-timeout", () -> "5s");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.minimum-number-of-calls", () -> "1");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.sliding-window-size", () -> "10");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.failure-rate-threshold", () -> "50");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.wait-duration-in-open-state", () -> "1s");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.automatic-transition-from-open-to-half-open-enabled", () -> "true");
        registry.add("resilience4j.ratelimiter.instances.partnerApi.limit-for-period", () -> "100");
        registry.add("resilience4j.retry.instances.partnerApi.max-attempts", () -> "3");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.wait-duration-in-open-state", () -> "1s");
        registry.add("demo.fault-injection-enabled", () -> "true");
    }

    @BeforeEach
    void resetResilienceAndPartner() throws InterruptedException {
        partner.resetAll();
        circuitBreakerRegistry.circuitBreaker("partnerApi").reset();
        rateLimiterRegistry.rateLimiter("partnerApi").drainPermissions();
        rateLimiterRegistry.rateLimiter("partnerApi").changeLimitForPeriod(100);
        Thread.sleep(1_100);
    }

    @Test
    void normalFulfillmentSucceedsAndDuplicateReturnsStoredResult() {
        partner.resetAll();
        partner.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-X","orderId":"ORDER-1001","status":"COMPLETED","partnerReference":"PARTNER-1"}
                """)));

        String key = "dup-key-" + UUID.randomUUID();
        SimpleResponse first = submit(key, body("ORDER-1001"));
        SimpleResponse second = submit(key, body("ORDER-1001"));

        assertThat(first.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.status()).isEqualTo(HttpStatus.OK);
        partner.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void sameKeyDifferentPayloadReturnsConflict() {
        partner.resetAll();
        partner.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-X","orderId":"ORDER-1002","status":"COMPLETED","partnerReference":"PARTNER-2"}
                """)));
        String key = "conflict-key-" + UUID.randomUUID();

        assertThat(submit(key, body("ORDER-1002")).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(submit(key, body("ORDER-OTHER")).status()).isEqualTo(HttpStatus.CONFLICT);
        partner.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void concurrentDuplicateRequestsCreateOnePartnerOperation() throws Exception {
        partner.resetAll();
        partner.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-C","orderId":"ORDER-C","status":"COMPLETED","partnerReference":"PARTNER-C"}
                """)));
        String key = "concurrent-key-" + UUID.randomUUID();
        Callable<HttpStatusCode> task = () -> submit(key, body("ORDER-C")).status();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<HttpStatusCode> statuses = executor.invokeAll(List.of(task, task, task, task)).stream()
                    .map(future -> {
                        try { return future.get(); } catch (Exception ex) { throw new RuntimeException(ex); }
                    })
                    .toList();
            assertThat(statuses).contains(HttpStatus.CREATED);
            partner.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void serverFailureRetriesButBadRequestDoesNot() {
        partner.stubFor(post("/partner/fulfillments")
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willSetStateTo("second")
                .willReturn(aResponse().withStatus(500).withFixedDelay(10)));
        partner.stubFor(post("/partner/fulfillments")
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(okJson("""
                        {"partnerRequestId":"REQ-R","orderId":"ORDER-R","status":"COMPLETED","partnerReference":"PARTNER-R"}
                        """)));

        SimpleResponse retried = submit("retry-key-" + UUID.randomUUID(), body("ORDER-R"));
        assertThat(retried.status()).as("response body: %s", retried.body()).isEqualTo(HttpStatus.CREATED);
        partner.verify(2, postRequestedFor(urlEqualTo("/partner/fulfillments")));

        partner.resetAll();
        partner.stubFor(post("/partner/fulfillments").willReturn(badRequest()));
        assertThat(submit("bad-key-" + UUID.randomUUID(), body("ORDER-BAD")).status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        partner.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void timeoutAfterCommitIsReconciledWithoutDuplicateWrite() {
        partner.stubFor(post("/partner/fulfillments").willReturn(aResponse().withFixedDelay(6_000).withBody("""
                {"partnerRequestId":"REQ-T","orderId":"ORDER-T","status":"COMPLETED","partnerReference":"PARTNER-T"}
                """).withHeader("Content-Type", "application/json")));
        partner.stubFor(get(urlPathMatching("/partner/fulfillments/.*")).willReturn(okJson("""
                {"partnerRequestId":"REQ-T","orderId":"ORDER-T","status":"COMPLETED","partnerReference":"PARTNER-T"}
                """)));

        SimpleResponse response = submit("timeout-key-" + UUID.randomUUID(), body("ORDER-T"));

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.body().get("status")).isEqualTo("COMPLETED");
        partner.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void correlationIdIsPropagatedAndMetricsEndpointIsAvailable() {
        partner.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-CORR","orderId":"ORDER-CORR","status":"COMPLETED","partnerReference":"PARTNER-CORR"}
                """)));
        String correlationId = "corr-" + UUID.randomUUID();
        HttpHeaders headers = headers("corr-key-" + UUID.randomUUID());
        headers.set("X-Correlation-ID", correlationId);

        SimpleResponse response = submit(headers, body("ORDER-CORR"));

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        partner.verify(postRequestedFor(urlEqualTo("/partner/fulfillments")).withHeader("X-Correlation-ID", equalTo(correlationId)));
        assertThat(getText("http://localhost:" + port + "/actuator/prometheus")).contains("integration_requests_total");
    }

    @Test
    void partnerRateLimitRetriesThreeTimesThenReturns503AndReplayIsStable() {
        partner.stubFor(post("/partner/fulfillments").willReturn(aResponse().withStatus(429)));
        String key = "partner-429-" + UUID.randomUUID();

        SimpleResponse first = submit(key, body("ORDER-429"));
        SimpleResponse replay = submit(key, body("ORDER-429"));

        assertThat(first.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(first.body().get("failureCode")).isEqualTo("PARTNER_RATE_LIMITED");
        assertThat(replay.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        partner.verify(3, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void serverFailureExhaustsRetriesAndReturns502() {
        partner.stubFor(post("/partner/fulfillments").willReturn(serverError()));

        SimpleResponse response = submit("exhausted-5xx-" + UUID.randomUUID(), body("ORDER-EXHAUSTED"));

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.body().get("failureCode")).isEqualTo("PARTNER_FAILURE");
        partner.verify(3, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void outboundRateLimiterReturns429AndDoesNotCallPartner() {
        rateLimiterRegistry.rateLimiter("partnerApi").changeLimitForPeriod(1);
        rateLimiterRegistry.rateLimiter("partnerApi").drainPermissions();
        try {
            Thread.sleep(1_100);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for limiter refresh", ex);
        }
        String retryKey = "limit-retry-" + UUID.randomUUID();
        partner.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-LIMIT","orderId":"ORDER-LIMIT","status":"COMPLETED","partnerReference":"PARTNER-LIMIT"}
                """)));

        assertThat(submit("limit-1-" + UUID.randomUUID(), body("ORDER-LIMIT-1")).status())
                .isEqualTo(HttpStatus.CREATED);
        SimpleResponse limited = submit(retryKey, body("ORDER-LIMIT-2"));

        assertThat(limited.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.body().get("failureCode")).isEqualTo("OUTBOUND_RATE_LIMITED");
        partner.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            SimpleResponse recovered = submit(retryKey, body("ORDER-LIMIT-2"));
            assertThat(recovered.status()).isEqualTo(HttpStatus.CREATED);
        });
    }

    @Test
    void circuitOpensThenRecoversAfterSuccessfulHalfOpenProbe() {
        partner.stubFor(post("/partner/fulfillments").willReturn(serverError()));
        for (int i = 0; i < 10
                && !circuitBreakerRegistry.circuitBreaker("partnerApi").getState().name().equals("OPEN"); i++) {
            submit("cb-fail-" + i + "-" + UUID.randomUUID(), body("ORDER-CB-" + i));
        }
        assertThat(circuitBreakerRegistry.circuitBreaker("partnerApi").getState().name()).isEqualTo("OPEN");

        SimpleResponse rejected = submit("cb-open-" + UUID.randomUUID(), body("ORDER-CB-OPEN"));
        assertThat(rejected.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rejected.body().get("failureCode")).isEqualTo("CIRCUIT_OPEN");

        partner.resetAll();
        partner.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-RECOVER","orderId":"ORDER-RECOVER","status":"COMPLETED","partnerReference":"PARTNER-RECOVER"}
                """)));
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(circuitBreakerRegistry.circuitBreaker("partnerApi").getState().name()).isEqualTo("HALF_OPEN"));
        assertThat(submit("cb-recover-" + UUID.randomUUID(), body("ORDER-RECOVER")).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(circuitBreakerRegistry.circuitBreaker("partnerApi").getState().name()).isEqualTo("CLOSED");
    }

    @Test
    void oversizedHeadersAndFieldsReturnBadRequest() {
        HttpHeaders oversizedIdempotency = headers("x".repeat(201));
        assertThat(submit(oversizedIdempotency, body("ORDER-LONG")).status()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> oversizedBody = Map.of("orderId", "x".repeat(101), "sku", "SKU", "quantity", 1);
        assertThat(submit("length-key-" + UUID.randomUUID(), oversizedBody).status()).isEqualTo(HttpStatus.BAD_REQUEST);

        HttpHeaders oversizedCorrelation = headers("correlation-length-" + UUID.randomUUID());
        oversizedCorrelation.set("X-Correlation-ID", "c".repeat(129));
        assertThat(submit(oversizedCorrelation, body("ORDER-CORRELATION-LONG")).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ambiguousTimeoutWithoutPartnerStatusRemainsAcceptedUnknown() {
        partner.stubFor(post("/partner/fulfillments").willReturn(aResponse().withFixedDelay(6_000).withBody("{}")
                .withHeader("Content-Type", "application/json")));
        partner.stubFor(get(urlPathMatching("/partner/fulfillments/.*"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.notFound()));

        SimpleResponse response = submit("timeout-unknown-" + UUID.randomUUID(), body("ORDER-TIMEOUT-UNKNOWN"));

        assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.body().get("status")).isEqualTo("UNKNOWN");
    }

    private SimpleResponse submit(String key, Map<String, Object> body) {
        return submit(headers(key), body);
    }

    private SimpleResponse submit(HttpHeaders headers, Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url()))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .header("Content-Type", "application/json");
            headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> responseBody = response.body() == null || response.body().isBlank()
                    ? Map.of()
                    : objectMapper.readValue(response.body(), new TypeReference<>() { });
            return new SimpleResponse(HttpStatusCode.valueOf(response.statusCode()), responseBody);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private String getText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private HttpHeaders headers(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return headers;
    }

    private Map<String, Object> body(String orderId) {
        return Map.of("orderId", orderId, "sku", "SKU-001", "quantity", 2);
    }

    private String url() {
        return "http://localhost:" + port + "/api/fulfillments";
    }

    private record SimpleResponse(HttpStatusCode status, Map<String, Object> body) {
    }
}

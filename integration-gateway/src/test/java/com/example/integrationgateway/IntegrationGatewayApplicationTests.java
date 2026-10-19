package com.example.integrationgateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationGatewayApplicationTests {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    static WireMockServer partner = new WireMockServer(0);

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

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
        registry.add("partner.read-timeout", () -> "500ms");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.minimum-number-of-calls", () -> "3");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.sliding-window-size", () -> "4");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.wait-duration-in-open-state", () -> "1s");
        registry.add("resilience4j.ratelimiter.instances.partnerApi.limit-for-period", () -> "3");
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
        partner.resetAll();
        partner.stubFor(post("/partner/fulfillments")
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willSetStateTo("second")
                .willReturn(serverError()));
        partner.stubFor(post("/partner/fulfillments")
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(okJson("""
                        {"partnerRequestId":"REQ-R","orderId":"ORDER-R","status":"COMPLETED","partnerReference":"PARTNER-R"}
                        """)));

        assertThat(submit("retry-key-" + UUID.randomUUID(), body("ORDER-R")).status()).isEqualTo(HttpStatus.CREATED);
        partner.verify(2, postRequestedFor(urlEqualTo("/partner/fulfillments")));

        partner.resetAll();
        partner.stubFor(post("/partner/fulfillments").willReturn(badRequest()));
        assertThat(submit("bad-key-" + UUID.randomUUID(), body("ORDER-BAD")).status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        partner.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void timeoutAfterCommitIsReconciledWithoutDuplicateWrite() {
        partner.resetAll();
        partner.stubFor(post("/partner/fulfillments").willReturn(aResponse().withFixedDelay(800).withBody("""
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
        partner.resetAll();
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

package com.example.integrationgateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

abstract class GatewayIntegrationTestSupport {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static final WireMockServer PARTNER = new WireMockServer(0);

    static {
        MYSQL.start();
        PARTNER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PARTNER.stop();
            MYSQL.stop();
        }));
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    RateLimiterRegistry rateLimiterRegistry;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("partner.base-url", PARTNER::baseUrl);
        registry.add("partner.read-timeout", () -> "5s");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.minimum-number-of-calls", () -> "1");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.sliding-window-size", () -> "10");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.failure-rate-threshold", () -> "50");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.wait-duration-in-open-state", () -> "1s");
        registry.add("resilience4j.circuitbreaker.instances.partnerApi.automatic-transition-from-open-to-half-open-enabled", () -> "true");
        registry.add("resilience4j.ratelimiter.instances.partnerApi.limit-for-period", () -> "100");
        registry.add("resilience4j.retry.instances.partnerApi.max-attempts", () -> "3");
        registry.add("resilience4j.retry.instances.partnerApi.wait-duration", () -> "1ms");
        registry.add("demo.fault-injection-enabled", () -> "true");
    }

    @BeforeEach
    void resetResilienceAndPartner() throws InterruptedException {
        PARTNER.resetAll();
        circuitBreakerRegistry.circuitBreaker("partnerApi").reset();
        rateLimiterRegistry.rateLimiter("partnerApi").drainPermissions();
        rateLimiterRegistry.rateLimiter("partnerApi").changeLimitForPeriod(100);
        Thread.sleep(1_100);
    }

    SimpleResponse submit(String key, Map<String, Object> body) {
        return submit(headers(key), body);
    }

    SimpleResponse submit(HttpHeaders headers, Map<String, Object> body) {
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

    String getText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    HttpHeaders headers(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return headers;
    }

    Map<String, Object> body(String orderId) {
        return Map.of("orderId", orderId, "sku", "SKU-001", "quantity", 2);
    }

    String url() {
        return "http://localhost:" + port + "/api/fulfillments";
    }

    record SimpleResponse(HttpStatusCode status, Map<String, Object> body) {
    }
}

package com.example.integrationgateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FulfillmentValidationAndObservabilityTests extends GatewayIntegrationTestSupport {

    @Test
    void oversizedIdempotencyKeyAndFieldsReturnBadRequest() {
        assertThat(submit("x".repeat(201), body("ORDER-LONG")).status()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> oversizedBody = Map.of("orderId", "x".repeat(101), "sku", "SKU", "quantity", 1);
        assertThat(submit("length-" + UUID.randomUUID(), oversizedBody).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void oversizedCorrelationIdReturnsBadRequest() {
        HttpHeaders headers = headers("correlation-length-" + UUID.randomUUID());
        headers.set("X-Correlation-ID", "c".repeat(129));

        assertThat(submit(headers, body("ORDER-CORRELATION-LONG")).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void correlationIdPropagatesAndMetricsAreExposed() {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-CORR","orderId":"ORDER-CORR","status":"COMPLETED","partnerReference":"PARTNER-CORR"}
                """)));
        String correlationId = "corr-" + UUID.randomUUID();
        HttpHeaders headers = headers("corr-key-" + UUID.randomUUID());
        headers.set("X-Correlation-ID", correlationId);

        assertThat(submit(headers, body("ORDER-CORR")).status()).isEqualTo(HttpStatus.CREATED);
        PARTNER.verify(postRequestedFor(urlEqualTo("/partner/fulfillments"))
                .withHeader("X-Correlation-ID", equalTo(correlationId)));
        assertThat(getText("http://localhost:" + port + "/actuator/prometheus"))
                .contains("integration_requests_total");
    }
}

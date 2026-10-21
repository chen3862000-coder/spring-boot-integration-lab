package com.example.integrationgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FulfillmentIdempotencyTests extends GatewayIntegrationTestSupport {

    @Test
    void duplicateRequestReturnsStoredResultWithoutCallingPartnerAgain() {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-IDEMPOTENT","orderId":"ORDER-1","status":"COMPLETED","partnerReference":"PARTNER-1"}
                """)));
        String key = "duplicate-" + UUID.randomUUID();

        SimpleResponse first = submit(key, body("ORDER-1"));
        SimpleResponse replay = submit(key, body("ORDER-1"));

        assertThat(first.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.status()).isEqualTo(HttpStatus.OK);
        PARTNER.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void sameKeyWithDifferentPayloadReturnsConflict() {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-CONFLICT","orderId":"ORDER-1","status":"COMPLETED","partnerReference":"PARTNER-1"}
                """)));
        String key = "conflict-" + UUID.randomUUID();

        assertThat(submit(key, body("ORDER-1")).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(submit(key, body("ORDER-2")).status()).isEqualTo(HttpStatus.CONFLICT);
        PARTNER.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
    }

    @Test
    void concurrentDuplicateRequestsCreateOnlyOnePartnerOperation() throws Exception {
        PARTNER.stubFor(post("/partner/fulfillments").willReturn(okJson("""
                {"partnerRequestId":"REQ-CONCURRENT","orderId":"ORDER-C","status":"COMPLETED","partnerReference":"PARTNER-C"}
                """)));
        String key = "concurrent-" + UUID.randomUUID();
        Callable<HttpStatus> task = () -> (HttpStatus) submit(key, body("ORDER-C")).status();
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<HttpStatus> statuses = executor.invokeAll(List.of(task, task, task, task)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    })
                    .toList();
            assertThat(statuses).contains(HttpStatus.CREATED);
            PARTNER.verify(1, postRequestedFor(urlEqualTo("/partner/fulfillments")));
        } finally {
            executor.shutdownNow();
        }
    }
}

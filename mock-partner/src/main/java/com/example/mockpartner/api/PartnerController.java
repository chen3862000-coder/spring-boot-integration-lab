package com.example.mockpartner.api;

import com.example.mockpartner.domain.PartnerFulfillment;
import com.example.mockpartner.domain.PartnerFulfillmentService;
import com.example.mockpartner.domain.PartnerFulfillmentService.PartnerRequest;
import com.example.mockpartner.fault.FaultScenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/partner")
public class PartnerController {
    private final PartnerFulfillmentService service;
    private final boolean faultInjectionEnabled;

    public PartnerController(
            PartnerFulfillmentService service,
            @Value("${demo.fault-injection-enabled:false}") boolean faultInjectionEnabled) {
        this.service = service;
        this.faultInjectionEnabled = faultInjectionEnabled;
    }

    @PostMapping("/fulfillments")
    public ResponseEntity<PartnerResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Partner-Fault", required = false) String fault,
            @Valid @RequestBody PartnerRequestBody body) throws InterruptedException {
        FaultScenario scenario = faultInjectionEnabled ? FaultScenario.from(fault) : FaultScenario.NORMAL;
        switch (scenario) {
            case HTTP_500 -> throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Injected partner HTTP 500");
            case HTTP_429 -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Injected partner HTTP 429");
            case SERVICE_UNAVAILABLE -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Injected partner outage");
            case SLOW_RESPONSE -> Thread.sleep(1_500);
            case TIMEOUT_AFTER_COMMIT -> {
                PartnerFulfillment committed = service.process(idempotencyKey, body.toPartnerRequest());
                Thread.sleep(3_000);
                return ResponseEntity.ok(PartnerResponse.from(committed));
            }
            case NORMAL -> { }
        }
        PartnerFulfillment fulfillment = service.process(idempotencyKey, body.toPartnerRequest());
        return ResponseEntity.ok(PartnerResponse.from(fulfillment));
    }

    @GetMapping("/fulfillments/{partnerRequestId}")
    public PartnerResponse get(@PathVariable String partnerRequestId) {
        return service.find(partnerRequestId)
                .map(PartnerResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", service.logicalOperations());
    }

    public record PartnerRequestBody(
            @NotBlank String partnerRequestId,
            @NotBlank String orderId,
            @NotBlank String sku,
            @Min(1) int quantity) {
        public PartnerRequest toPartnerRequest() {
            return new PartnerRequest(partnerRequestId, orderId, sku, quantity);
        }
    }

    public record PartnerResponse(String partnerRequestId, String orderId, String status, String partnerReference) {
        public static PartnerResponse from(PartnerFulfillment fulfillment) {
            return new PartnerResponse(
                    fulfillment.partnerRequestId(),
                    fulfillment.orderId(),
                    fulfillment.status(),
                    fulfillment.partnerReference());
        }
    }

    public record HealthResponse(String status, int logicalOperations) {
    }
}

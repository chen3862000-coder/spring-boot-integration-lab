package com.example.integrationgateway.api;

import com.example.integrationgateway.application.FulfillmentService;
import com.example.integrationgateway.application.SubmitResult;
import com.example.integrationgateway.persistence.FulfillmentRecord;
import com.example.integrationgateway.persistence.FulfillmentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@Validated
@RequestMapping("/api/fulfillments")
public class FulfillmentController {

    private final FulfillmentService fulfillmentService;

    public FulfillmentController(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @PostMapping
    ResponseEntity<FulfillmentResponse> submit(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader(value = "X-Partner-Fault", required = false) @Size(max = 40) String partnerFault,
            @Valid @RequestBody FulfillmentRequest request) {
        SubmitResult result = fulfillmentService.submit(idempotencyKey, partnerFault, request);
        HttpStatus status = responseStatus(result.record(), result.created());
        return ResponseEntity.status(status)
                .location(URI.create("/api/fulfillments/" + result.record().requestId()))
                .body(FulfillmentResponse.from(result.record()));
    }

    @GetMapping("/{requestId}")
    ResponseEntity<FulfillmentResponse> get(@PathVariable String requestId) {
        return fulfillmentService.find(requestId)
                .map(FulfillmentResponse::from)
                .map(response -> ResponseEntity
                        .status(responseStatus(response.status(), response.failureCode(), false))
                        .body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private HttpStatus responseStatus(FulfillmentRecord record, boolean created) {
        return responseStatus(record.status(), record.failureCode(), created);
    }

    private HttpStatus responseStatus(FulfillmentStatus status, String failureCode, boolean created) {
        if ("OUTBOUND_RATE_LIMITED".equals(failureCode)) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if ("CIRCUIT_OPEN".equals(failureCode)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if ("PARTNER_RATE_LIMITED".equals(failureCode)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if ("PARTNER_FAILURE".equals(failureCode)) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (status == FulfillmentStatus.COMPLETED) {
            return created ? HttpStatus.CREATED : HttpStatus.OK;
        }
        if (status == FulfillmentStatus.FAILED) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.ACCEPTED;
    }

    public record FulfillmentRequest(
            @NotBlank @Size(max = 100) String orderId,
            @NotBlank @Size(max = 100) String sku,
            @Min(1) int quantity) {
    }
}

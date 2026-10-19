package com.example.integrationgateway.api;

import com.example.integrationgateway.application.FulfillmentService;
import com.example.integrationgateway.application.SubmitResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/fulfillments")
public class FulfillmentController {

    private final FulfillmentService fulfillmentService;

    public FulfillmentController(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @PostMapping
    ResponseEntity<FulfillmentResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Partner-Fault", required = false) String partnerFault,
            @Valid @RequestBody FulfillmentRequest request) {
        SubmitResult result = fulfillmentService.submit(idempotencyKey, partnerFault, request);
        HttpStatus status = switch (result.record().status()) {
            case COMPLETED -> result.created() ? HttpStatus.CREATED : HttpStatus.OK;
            case PROCESSING, UNKNOWN -> HttpStatus.ACCEPTED;
            case FAILED -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status)
                .location(URI.create("/api/fulfillments/" + result.record().requestId()))
                .body(FulfillmentResponse.from(result.record()));
    }

    @GetMapping("/{requestId}")
    ResponseEntity<FulfillmentResponse> get(@PathVariable String requestId) {
        return fulfillmentService.find(requestId)
                .map(FulfillmentResponse::from)
                .map(response -> ResponseEntity
                        .status(response.status().isTerminalSuccess() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                        .body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record FulfillmentRequest(@NotBlank String orderId, @NotBlank String sku, @Min(1) int quantity) {
    }
}

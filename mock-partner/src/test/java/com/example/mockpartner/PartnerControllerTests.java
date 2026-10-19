package com.example.mockpartner;

import com.example.mockpartner.api.PartnerController;
import com.example.mockpartner.domain.PartnerFulfillmentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerControllerTests {

    private final PartnerFulfillmentService service = new PartnerFulfillmentService();
    private final PartnerController controller = new PartnerController(service);

    @Test
    void duplicateIdempotencyKeyReturnsSameLogicalOperation() throws Exception {
        PartnerController.PartnerRequestBody request = new PartnerController.PartnerRequestBody("REQ-1", "ORDER-1", "SKU-1", 1);

        ResponseEntity<PartnerController.PartnerResponse> first = controller.create("same-key", null, request);
        ResponseEntity<PartnerController.PartnerResponse> second = controller.create("same-key", null, request);

        assertThat(first.getBody()).isEqualTo(second.getBody());
        assertThat(service.logicalOperations()).isEqualTo(1);
    }
}

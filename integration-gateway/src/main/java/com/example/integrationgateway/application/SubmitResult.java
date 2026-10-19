package com.example.integrationgateway.application;

import com.example.integrationgateway.persistence.FulfillmentRecord;

public record SubmitResult(FulfillmentRecord record, boolean created) {
}

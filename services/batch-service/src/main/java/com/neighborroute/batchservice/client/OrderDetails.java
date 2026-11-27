package com.neighborroute.batchservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderDetails(
        UUID id,
        Long storeId,
        String deliveryBuilding,
        String status
) {
}
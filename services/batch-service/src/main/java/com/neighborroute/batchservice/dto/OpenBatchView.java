package com.neighborroute.batchservice.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record OpenBatchView(
        UUID id,
        Long storeId,
        String deliveryBuilding,
        Instant expiresAt,
        long activeOrders
) implements Serializable {
}
package com.neighborroute.batchservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BatchDetailsResponse(
        UUID batchId,
        Long storeId,
        String deliveryBuilding,
        String status,
        Instant expiresAt,
        List<UUID> activeOrderIds
) {}
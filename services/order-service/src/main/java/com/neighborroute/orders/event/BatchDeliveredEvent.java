package com.neighborroute.orders.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BatchDeliveredEvent(
        UUID eventId,
        String eventType,
        UUID batchId,
        List<UUID> orderIds,
        Instant occurredAt
) {}
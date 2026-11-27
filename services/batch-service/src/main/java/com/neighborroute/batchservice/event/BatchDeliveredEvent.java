package com.neighborroute.batchservice.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BatchDeliveredEvent(
        UUID eventId,
        UUID batchId,
        List<UUID> orderIds,
        Instant occurredAt
) {}
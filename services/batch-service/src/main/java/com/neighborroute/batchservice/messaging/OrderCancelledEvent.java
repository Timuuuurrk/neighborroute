package com.neighborroute.batchservice.messaging;

import java.util.UUID;

public record OrderCancelledEvent(
        UUID eventId,
        String eventType,
        UUID orderId
) {}
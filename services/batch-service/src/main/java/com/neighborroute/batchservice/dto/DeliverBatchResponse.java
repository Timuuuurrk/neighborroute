package com.neighborroute.batchservice.dto;

import java.util.UUID;

public record DeliverBatchResponse(
        UUID batchId,
        String outcome
) {}
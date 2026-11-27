package com.neighborroute.batchservice.dto;

import java.util.UUID;

public record CloseBatchResponse(
        UUID batchId,
        String outcome
) {}
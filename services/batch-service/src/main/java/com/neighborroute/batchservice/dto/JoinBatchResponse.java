package com.neighborroute.batchservice.dto;

import com.neighborroute.batchservice.service.JoinBatchOutcome;
import java.util.UUID;

public record JoinBatchResponse(
        UUID batchId,
        JoinBatchOutcome outcome
) {}
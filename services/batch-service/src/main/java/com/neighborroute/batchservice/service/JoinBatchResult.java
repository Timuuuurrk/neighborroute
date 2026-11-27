package com.neighborroute.batchservice.service;

import java.util.UUID;

public record JoinBatchResult(
        UUID batchId,
        JoinBatchOutcome outcome
) {}
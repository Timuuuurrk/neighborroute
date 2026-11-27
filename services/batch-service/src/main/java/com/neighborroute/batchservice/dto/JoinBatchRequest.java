package com.neighborroute.batchservice.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record JoinBatchRequest(
        @NotNull UUID orderId
) {}
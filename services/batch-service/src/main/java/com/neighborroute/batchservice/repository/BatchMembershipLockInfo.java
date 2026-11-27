package com.neighborroute.batchservice.repository;

import java.util.UUID;

public record BatchMembershipLockInfo(
        UUID batchId,
        Long storeId,
        String deliveryBuilding
) {}
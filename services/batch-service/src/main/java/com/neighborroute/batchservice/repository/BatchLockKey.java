package com.neighborroute.batchservice.repository;

public record BatchLockKey(
        Long storeId,
        String deliveryBuilding
) {}
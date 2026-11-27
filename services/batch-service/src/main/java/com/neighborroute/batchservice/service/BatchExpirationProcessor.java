package com.neighborroute.batchservice.service;

import com.neighborroute.batchservice.cache.OpenBatchCacheInvalidator;
import com.neighborroute.batchservice.domain.Batch;
import com.neighborroute.batchservice.domain.BatchOrderStatus;
import com.neighborroute.batchservice.domain.BatchStatus;
import com.neighborroute.batchservice.repository.BatchLockKey;
import com.neighborroute.batchservice.repository.BatchOrderRepository;
import com.neighborroute.batchservice.repository.BatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class BatchExpirationProcessor {

    private final BatchRepository batchRepository;
    private final BatchOrderRepository batchOrderRepository;
    private final BatchCreationLock batchCreationLock;
    private final OpenBatchCacheInvalidator cacheInvalidator;

    public BatchExpirationProcessor(
            BatchRepository batchRepository,
            BatchOrderRepository batchOrderRepository,
            BatchCreationLock batchCreationLock,
            OpenBatchCacheInvalidator cacheInvalidator
    ) {
        this.batchRepository = batchRepository;
        this.batchOrderRepository = batchOrderRepository;
        this.batchCreationLock = batchCreationLock;
        this.cacheInvalidator=cacheInvalidator;
    }

    @Transactional
    public int closeOneBatch(UUID batchId) {

        Optional<BatchLockKey> optionalKey =
                batchRepository.findLockKeyById(batchId);

        if (optionalKey.isEmpty()) {
            return 0;
        }

        BatchLockKey key = optionalKey.get();

        batchCreationLock.lock(
                key.storeId(),
                key.deliveryBuilding()
        );

        Optional<Batch> optionalBatch =
                batchRepository.findById(batchId);

        if (optionalBatch.isEmpty()) {
            return 0;
        }

        Batch batch = optionalBatch.get();
        Instant now = Instant.now();

        if (batch.getStatus() != BatchStatus.OPEN
                || batch.getExpiresAt().isAfter(now)) {
            return 0;
        }

        long activeOrders = batchOrderRepository
                .countByBatch_IdAndStatus(
                        batchId,
                        BatchOrderStatus.ACTIVE
                );

        if (activeOrders > 0) {
            batch.close();
        } else {
            batch.expire();
        }

        cacheInvalidator.evictAfterCommit(
                batch.getStoreId(),
                batch.getDeliveryBuilding()
        );

        return 1;
    }
}

package com.neighborroute.batchservice.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OpenBatchCacheInvalidator {

    private static final Logger log =
            LoggerFactory.getLogger(OpenBatchCacheInvalidator.class);

    private final CacheManager cacheManager;

    public OpenBatchCacheInvalidator(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void evictAfterCommit(Long storeId, String deliveryBuilding) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Cache invalidation must be registered inside a transaction"
            );
        }

        String key = storeId + ":" + deliveryBuilding;

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            Cache cache = cacheManager.getCache("open-batches");

                            if (cache != null) {
                                cache.evict(key);
                                log.info("Evicted open-batches cache: {}", key);
                            }
                        } catch (RuntimeException ex) {
                            log.warn("Failed to evict cache key {}", key, ex);
                        }
                    }
                }
        );
    }
}

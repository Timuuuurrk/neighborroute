
package com.neighborroute.batchservice.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "batch.expiration.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class BatchExpirationScheduler {

    private final BatchExpirationService expirationService;

    public BatchExpirationScheduler(
            BatchExpirationService expirationService
    ) {
        this.expirationService = expirationService;
    }

    @Scheduled(fixedDelay = 30_000)
    public void closeExpiredBatches() {
        expirationService.closeExpiredBatches();
    }
}

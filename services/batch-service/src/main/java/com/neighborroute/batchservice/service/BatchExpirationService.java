
package com.neighborroute.batchservice.service;

import com.neighborroute.batchservice.domain.Batch;
import com.neighborroute.batchservice.domain.BatchOrderStatus;
import com.neighborroute.batchservice.domain.BatchStatus;
import com.neighborroute.batchservice.repository.BatchRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BatchExpirationService {

    private final BatchRepository batchRepository;
    private final BatchExpirationProcessor processor;

    public BatchExpirationService(
            BatchRepository batchRepository,
            BatchExpirationProcessor processor
    ) {
        this.batchRepository = batchRepository;
        this.processor = processor;
    }

    public int closeExpiredBatches() {
        List<UUID> ids = batchRepository.findExpiredBatchIds(
                BatchStatus.OPEN,
                Instant.now()
        );

        int processed = 0;

        for (UUID id : ids) {
            processed += processor.closeOneBatch(id);
        }

        return processed;
    }
}
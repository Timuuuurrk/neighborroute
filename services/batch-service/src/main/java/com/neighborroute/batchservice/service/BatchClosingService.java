package com.neighborroute.batchservice.service;

import com.neighborroute.batchservice.cache.OpenBatchCacheInvalidator;
import com.neighborroute.batchservice.dto.CloseBatchResponse;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BatchClosingService {

    private final JdbcTemplate jdbcTemplate;
    private final OpenBatchCacheInvalidator cacheInvalidator;

    public BatchClosingService(
            JdbcTemplate jdbcTemplate,
            OpenBatchCacheInvalidator cacheInvalidator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.cacheInvalidator = cacheInvalidator;
    }

    @Transactional
    public CloseBatchResponse closeBatch(UUID batchId) {
        List<BatchSnapshot> batches = jdbcTemplate.query(
                """
                SELECT store_id, delivery_building, status, expires_at
                FROM batches
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new BatchSnapshot(
                        rs.getLong("store_id"),
                        rs.getString("delivery_building"),
                        rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant()
                ),
                batchId
        );

        if (batches.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Batch not found"
            );
        }

        BatchSnapshot batch = batches.get(0);

        if ("CLOSED".equals(batch.status())) {
            return new CloseBatchResponse(batchId, "ALREADY_CLOSED");
        }

        if (!"OPEN".equals(batch.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only OPEN batches can be closed"
            );
        }

        if (!batch.expiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Batch has already expired"
            );
        }

        jdbcTemplate.update(
                """
                UPDATE batches
                SET status = 'CLOSED'
                WHERE id = ?
                """,
                batchId
        );

        cacheInvalidator.evictAfterCommit(
                batch.storeId(),
                batch.building()
        );

        return new CloseBatchResponse(batchId, "CLOSED");
    }

    private record BatchSnapshot(
            Long storeId,
            String building,
            String status,
            Instant expiresAt
    ) {}
}


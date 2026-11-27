package com.neighborroute.batchservice.service;

import com.neighborroute.batchservice.dto.BatchDetailsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class BatchDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public BatchDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public BatchDetailsResponse getBatch(UUID batchId) {
        List<BatchDetailsResponse> batches = jdbcTemplate.query(
                """
                SELECT id, store_id, delivery_building,
                       status, expires_at
                FROM batches
                WHERE id = ?
                """,
                (rs, rowNum) -> new BatchDetailsResponse(
                        rs.getObject("id", UUID.class),
                        rs.getLong("store_id"),
                        rs.getString("delivery_building"),
                        rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant(),
                        List.of()
                ),
                batchId
        );

        if (batches.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Сессия не найдена"
            );
        }

        BatchDetailsResponse batch = batches.get(0);

        List<UUID> orderIds = jdbcTemplate.query(
                """
                SELECT order_id
                FROM batch_orders
                WHERE batch_id = ?
                  AND status = 'ACTIVE'
                ORDER BY order_id
                """,
                (rs, rowNum) -> rs.getObject(
                        "order_id", UUID.class
                ),
                batchId
        );

        return new BatchDetailsResponse(
                batch.batchId(),
                batch.storeId(),
                batch.deliveryBuilding(),
                batch.status(),
                batch.expiresAt(),
                orderIds
        );
    }
}
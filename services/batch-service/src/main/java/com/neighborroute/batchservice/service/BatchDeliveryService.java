package com.neighborroute.batchservice.service;

import com.neighborroute.batchservice.dto.DeliverBatchResponse;
import com.neighborroute.batchservice.event.BatchDeliveredEvent;
import com.neighborroute.batchservice.outbox.OutboxEventWriter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BatchDeliveryService {

    private final JdbcTemplate jdbcTemplate;

    private final OutboxEventWriter outboxEventWriter;

    public BatchDeliveryService(JdbcTemplate jdbcTemplate, OutboxEventWriter outboxEventWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxEventWriter=outboxEventWriter;
    }

    @Transactional
    public DeliverBatchResponse deliverBatch(UUID batchId) {

        List<String> statuses = jdbcTemplate.query(
                """
                SELECT status
                FROM batches
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> rs.getString("status"),
                batchId
        );

        if (statuses.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Batch not found"
            );
        }

        String status = statuses.get(0);

        if ("DELIVERED".equals(status)) {
            return new DeliverBatchResponse(
                    batchId,
                    "ALREADY_DELIVERED"
            );
        }

        if (!"CLOSED".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only CLOSED batches can be delivered"
            );
        }

        jdbcTemplate.update(
                """
                UPDATE batches
                SET status = 'DELIVERED'
                WHERE id = ?
                """,
                batchId
        );

        List<UUID> orderIds = jdbcTemplate.query(
                """
                SELECT order_id
                FROM batch_orders
                WHERE batch_id = ?
                  AND status = 'ACTIVE'
                ORDER BY order_id
                """,
                (rs, rowNum) ->
                        rs.getObject("order_id", UUID.class),
                batchId
        );

        BatchDeliveredEvent event = new BatchDeliveredEvent(
                UUID.randomUUID(),
                batchId,
                List.copyOf(orderIds),
                Instant.now()
        );

        outboxEventWriter.appendBatchDelivered(event);

        return new DeliverBatchResponse(
                batchId,
                "DELIVERED"
        );
    }
}

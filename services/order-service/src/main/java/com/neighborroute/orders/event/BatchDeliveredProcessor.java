
package com.neighborroute.orders.event;

import com.neighborroute.orders.domain.Order;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class BatchDeliveredProcessor {

    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public BatchDeliveredProcessor(
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    @Transactional
    public void process(BatchDeliveredEvent event) {

        if (event == null
                || event.eventId() == null
                || event.batchId() == null
                || event.orderIds() == null
                || !event.orderIds().stream().allMatch(
                id -> id != null)
                || !"BatchDelivered".equals(event.eventType())) {

            throw new IllegalArgumentException(
                    "Invalid BatchDelivered event"
            );
        }

        if (new HashSet<>(event.orderIds()).size()
                != event.orderIds().size()) {
            throw new IllegalArgumentException(
                    "Duplicate order IDs in event"
            );
        }

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO inbox_events (
                    event_id,
                    aggregate_id,
                    event_type
                )
                VALUES (?, ?, 'BatchDelivered')
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.eventId(),
                event.batchId()
        );

        if (inserted == 0) {
            return;
        }

        List<UUID> orderIds = event.orderIds()
                .stream()
                .sorted()
                .toList();

        for (UUID orderId : orderIds) {

            Order order = entityManager.find(
                    Order.class,
                    orderId,
                    LockModeType.PESSIMISTIC_WRITE
            );

            if (order == null) {
                throw new IllegalStateException(
                        "Order not found: " + orderId
                );
            }

            order.deliver();
        }

        entityManager.flush();
    }
}

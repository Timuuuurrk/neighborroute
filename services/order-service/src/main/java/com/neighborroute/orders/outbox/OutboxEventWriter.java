package com.neighborroute.orders.outbox;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class OutboxEventWriter {

    private final JdbcTemplate jdbcTemplate;

    public OutboxEventWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void appendOrderCancelled(UUID orderId) {
        UUID eventId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (
                    id,
                    aggregate_id,
                    event_type,
                    payload
                )
                VALUES (
                    ?,
                    ?,
                    'OrderCancelled',
                    jsonb_build_object(
                        'eventId', CAST(? AS text),
                        'eventType', 'OrderCancelled',
                        'orderId', CAST(? AS text)
                    )
                )
                """,
                eventId,
                orderId,
                eventId.toString(),
                orderId.toString()
        );
    }
}


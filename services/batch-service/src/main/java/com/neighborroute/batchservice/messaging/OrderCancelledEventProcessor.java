package com.neighborroute.batchservice.messaging;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCancelledEventProcessor {

    private final JdbcTemplate jdbcTemplate;

    public OrderCancelledEventProcessor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean process(
            OrderCancelledEvent event,
            String payload
    ) {
        if (event.eventId() == null || event.orderId() == null) {
            throw new IllegalArgumentException(
                    "Missing eventId or orderId"
            );
        }

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO inbox_events (
                    event_id,
                    event_type,
                    aggregate_id,
                    payload
                )
                VALUES (?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.eventId(),
                event.eventType(),
                event.orderId(),
                payload
        );

        return inserted == 1;
    }
}

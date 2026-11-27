
package com.neighborroute.batchservice.outbox;

import com.neighborroute.batchservice.event.BatchDeliveredEvent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class OutboxEventWriter {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public OutboxEventWriter(
            JdbcTemplate jdbcTemplate,
            JsonMapper jsonMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    public void appendBatchDelivered(BatchDeliveredEvent event) {
        String payload = jsonMapper.writeValueAsString(event);

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
                    'BatchDelivered',
                    CAST(? AS jsonb)
                      || jsonb_build_object(
                            'eventType', 'BatchDelivered'
                         )
                )
                """,
                event.eventId(),
                event.batchId(),
                payload
        );
    }
}


package com.neighborroute.telegrambot.kafka;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Component
public class BatchDeliveredListener {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public BatchDeliveredListener(
            JdbcTemplate jdbcTemplate,
            JsonMapper jsonMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    @KafkaListener(topics = "batches.events.v1")
    public void onMessage(String payload) {

        JsonNode event = jsonMapper.readTree(payload);

        String eventType =
                event.path("eventType").asText();

        if (!"BatchDelivered".equals(eventType)) {
            return;
        }

        UUID eventId = UUID.fromString(
                event.path("eventId").asText()
        );

        UUID batchId = UUID.fromString(
                event.path("batchId").asText()
        );

        JsonNode orderIds = event.path("orderIds");

        if (!orderIds.isArray()) {
            throw new IllegalArgumentException(
                    "BatchDelivered has invalid orderIds"
            );
        }

        for (JsonNode orderNode : orderIds) {
            UUID orderId = UUID.fromString(
                    orderNode.asText()
            );

            jdbcTemplate.update("""
                    INSERT INTO bot_notifications (
                        order_id,
                        event_id,
                        telegram_user_id,
                        batch_id
                    )
                    SELECT
                        bo.order_id,
                        ?,
                        bo.telegram_user_id,
                        ?
                    FROM bot_orders bo
                    WHERE bo.order_id = ?
                      AND bo.batch_id = ?
                      AND bo.join_status = 'JOINED'
                    ON CONFLICT (
                        order_id, notification_type
                    ) DO NOTHING
                    """,
                    eventId,
                    batchId,
                    orderId,
                    batchId
            );
        }
    }
}

package com.neighborroute.orders.outbox;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
        name = "neighborroute.outbox.publisher.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublisher(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            fixedDelayString = "${neighborroute.outbox.delay-ms:3000}",
            initialDelayString = "${neighborroute.outbox.initial-delay-ms:10000}"
    )
    public void publishPending() {
        for (int i = 0; i < 20; i++) {
            Boolean success = transactionTemplate.execute(
                    status -> publishOne()
            );

            if (!Boolean.TRUE.equals(success)) {
                break;
            }
        }
    }

    private boolean publishOne() {
        List<OutboxMessage> messages = jdbcTemplate.query(
                """
                SELECT id, aggregate_id, payload::text AS payload
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY created_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxMessage(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("payload")
                )
        );

        if (messages.isEmpty()) {
            return false;
        }

        OutboxMessage message = messages.get(0);

        try {
            kafkaTemplate.send(
                    "orders.events.v1",
                    message.aggregateId().toString(),
                    message.payload()
            ).get(10, TimeUnit.SECONDS);

            jdbcTemplate.update(
                    """
                    UPDATE outbox_events
                    SET published_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    message.id()
            );

            log.info("Published outbox event {}", message.id());
            return true;

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordFailure(message.id(), ex);
            return false;

        } catch (ExecutionException | TimeoutException
                 | RuntimeException ex) {
            recordFailure(message.id(), ex);
            return false;
        }
    }

    private void recordFailure(UUID eventId, Exception ex) {
        jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET attempts = attempts + 1
                WHERE id = ?
                """,
                eventId
        );

        log.warn("Failed to publish event {}", eventId, ex);
    }

    private record OutboxMessage(
            UUID id,
            UUID aggregateId,
            String payload
    ) {}
}


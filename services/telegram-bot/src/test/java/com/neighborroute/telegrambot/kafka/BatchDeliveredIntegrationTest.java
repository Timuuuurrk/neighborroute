
package com.neighborroute.telegrambot.kafka;

import com.neighborroute.telegrambot.notification.NotificationRepository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class BatchDeliveredIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    static JdbcTemplate jdbcTemplate;
    static BatchDeliveredListener listener;
    static NotificationRepository repository;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource();

        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);

        listener = new BatchDeliveredListener(
                jdbcTemplate,
                JsonMapper.builder().build()
        );

        repository = new NotificationRepository(jdbcTemplate);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bot_notifications");
        jdbcTemplate.update("DELETE FROM bot_orders");
        jdbcTemplate.update("DELETE FROM bot_users");
    }

    @Test
    void shouldIgnoreDuplicateDeliveredEvent() {
        long userId = 123456L;
        UUID orderId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        registerUser(userId);
        registerOrder(userId, orderId, batchId);

        String event = deliveredEvent(
                eventId, batchId, orderId
        );

        listener.onMessage(event);
        listener.onMessage(event);

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM bot_notifications
                WHERE order_id = ?
                """,
                Integer.class,
                orderId
        );

        assertEquals(1, count);

        String status = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM bot_notifications
                WHERE order_id = ?
                """,
                String.class,
                orderId
        );

        assertEquals("PENDING", status);
    }

    @Test
    void shouldIgnoreUnknownOrder() {
        UUID batchId = UUID.randomUUID();
        UUID unknownOrderId = UUID.randomUUID();

        listener.onMessage(deliveredEvent(
                UUID.randomUUID(),
                batchId,
                unknownOrderId
        ));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bot_notifications",
                Integer.class
        );

        assertEquals(0, count);
    }

    @Test
    void shouldClaimNotificationOnlyOnce() {
        long userId = 123456L;
        UUID orderId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        registerUser(userId);
        registerOrder(userId, orderId, batchId);

        listener.onMessage(deliveredEvent(
                UUID.randomUUID(),
                batchId,
                orderId
        ));

        var firstClaim = repository.claimNext();
        var secondClaim = repository.claimNext();

        assertTrue(firstClaim.isPresent());
        assertEquals(orderId, firstClaim.get().orderId());
        assertTrue(secondClaim.isEmpty());

        repository.markSent(orderId);

        String status = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM bot_notifications
                WHERE order_id = ?
                """,
                String.class,
                orderId
        );

        assertEquals("SENT", status);
        assertTrue(repository.claimNext().isEmpty());
    }

    private void registerUser(long userId) {
        jdbcTemplate.update("""
                INSERT INTO bot_users (
                    telegram_user_id, customer_id
                )
                VALUES (?, ?)
                """,
                userId,
                UUID.randomUUID()
        );
    }

    private void registerOrder(
            long userId,
            UUID orderId,
            UUID batchId
    ) {
        jdbcTemplate.update("""
                INSERT INTO bot_orders (
                    order_id,
                    telegram_user_id,
                    batch_id,
                    join_status
                )
                VALUES (?, ?, ?, 'JOINED')
                """,
                orderId,
                userId,
                batchId
        );
    }

    private String deliveredEvent(
            UUID eventId,
            UUID batchId,
            UUID orderId
    ) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "BatchDelivered",
                  "batchId": "%s",
                  "orderIds": ["%s"]
                }
                """.formatted(eventId, batchId, orderId);
    }


    @Test
    void shouldNotReclaimFailedNotification() {
        long userId = 123456L;
        UUID orderId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        registerUser(userId);
        registerOrder(userId, orderId, batchId);

        listener.onMessage(deliveredEvent(
                UUID.randomUUID(),
                batchId,
                orderId
        ));

        assertTrue(repository.claimNext().isPresent());

        repository.markReviewRequired(orderId);

        assertTrue(repository.claimNext().isEmpty());

        String status = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM bot_notifications
                WHERE order_id = ?
                """,
                String.class,
                orderId
        );

        assertEquals("REVIEW_REQUIRED", status);
    }


    @Test
    void shouldRecoverStaleSendingAsReviewRequired() {
        long userId = 123456L;
        UUID orderId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        registerUser(userId);
        registerOrder(userId, orderId, batchId);

        listener.onMessage(deliveredEvent(
                UUID.randomUUID(),
                batchId,
                orderId
        ));

        assertTrue(repository.claimNext().isPresent());

        jdbcTemplate.update(
                """
                UPDATE bot_notifications
                SET send_started_at =
                    CURRENT_TIMESTAMP - INTERVAL '20 minutes'
                WHERE order_id = ?
                """,
                orderId
        );

        int recovered =
                repository.markStaleSendingAsReviewRequired();

        assertEquals(1, recovered);

        String status = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM bot_notifications
                WHERE order_id = ?
                """,
                String.class,
                orderId
        );

        assertEquals("REVIEW_REQUIRED", status);

        assertTrue(repository.claimNext().isEmpty());
    }


}


package com.neighborroute.batchservice.integration;

import com.neighborroute.batchservice.outbox.OutboxPublisher;
import com.neighborroute.batchservice.service.BatchDeliveryService;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.cache.type=redis",
        "neighborroute.outbox.initial-delay-ms=3600000",
        "batch.expiration.scheduler.enabled=false"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BatchOutboxIntegrationTest {

    private static final String TOPIC = "batches.events.v1";

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(
                    DockerImageName.parse("redis:7-alpine")
            ).withExposedPorts(6379);

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(
                    DockerImageName.parse("apache/kafka:4.1.1")
            );

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );
        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );
        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );

        registry.add(
                "spring.data.redis.host",
                redis::getHost
        );
        registry.add(
                "spring.data.redis.port",
                () -> redis.getMappedPort(6379)
        );

        registry.add(
                "spring.kafka.bootstrap-servers",
                kafka::getBootstrapServers
        );
    }

    @BeforeAll
    static void createTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(
                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        kafka.getBootstrapServers()
                )
        )) {
            admin.createTopics(
                    List.of(new NewTopic(TOPIC, 3, (short) 1))
            ).all().get(20, TimeUnit.SECONDS);
        }
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    BatchDeliveryService deliveryService;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID createClosedBatch() {
        UUID id = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO batches (
                    id, store_id, delivery_building,
                    status, created_at, expires_at
                )
                VALUES (
                    ?, 1, ?, 'CLOSED',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour'
                )
                """,
                id,
                "Outbox Test " + id
        );

        return id;
    }

    private boolean isPublished(UUID batchId) {
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                        """
                        SELECT published_at IS NOT NULL
                        FROM outbox_events
                        WHERE aggregate_id = ?
                        """,
                        Boolean.class,
                        batchId
                )
        );
    }

    private int attempts(UUID batchId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT attempts
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                Integer.class,
                batchId
        );
    }

    private String savedPayload(UUID batchId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT payload::text
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                String.class,
                batchId
        );
    }

    private ConsumerRecord<String, String> readEvent(
            UUID batchId
    ) {
        Properties props = new Properties();

        props.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()
        );
        props.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                "outbox-test-" + UUID.randomUUID()
        );
        props.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );
        props.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );
        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props)) {

            consumer.subscribe(List.of(TOPIC));

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(20);

            while (System.nanoTime() < deadline) {

                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> record : records) {
                    if (batchId.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
        }

        throw new AssertionError(
                "Kafka event not found for batch " + batchId
        );
    }

    @Test
    void shouldPublishBatchDeliveredEvent() {
        UUID batchId = createClosedBatch();

        deliveryService.deliverBatch(batchId);

        assertFalse(isPublished(batchId));

        String expectedPayload = savedPayload(batchId);

        publisher.publishPending();

        assertTrue(isPublished(batchId));
        assertEquals(0, attempts(batchId));

        ConsumerRecord<String, String> record =
                readEvent(batchId);

        assertEquals(batchId.toString(), record.key());
        assertEquals(expectedPayload, record.value());
        assertTrue(record.value().contains("BatchDelivered"));
    }

    @Test
    void shouldRetryAfterKafkaFailure() {
        UUID batchId = createClosedBatch();

        deliveryService.deliverBatch(batchId);

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> failingKafka =
                mock(KafkaTemplate.class);

        when(failingKafka.send(
                anyString(),
                anyString(),
                anyString()
        )).thenThrow(
                new IllegalStateException("Simulated Kafka outage")
        );

        OutboxPublisher failingPublisher =
                new OutboxPublisher(
                        jdbcTemplate,
                        failingKafka,
                        transactionManager
                );

        failingPublisher.publishPending();

        assertFalse(isPublished(batchId));
        assertEquals(1, attempts(batchId));

        publisher.publishPending();

        assertTrue(isPublished(batchId));
        assertEquals(1, attempts(batchId));

        ConsumerRecord<String, String> record =
                readEvent(batchId);

        assertEquals(batchId.toString(), record.key());
        assertEquals(savedPayload(batchId), record.value());
    }
}

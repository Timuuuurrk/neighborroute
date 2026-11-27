package com.neighborroute.orders.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.neighborroute.orders.outbox.OutboxEventWriter;
import com.neighborroute.orders.outbox.OutboxPublisher;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "neighborroute.outbox.publisher.enabled=true",
        "neighborroute.outbox.delay-ms=3600000",
        "neighborroute.outbox.initial-delay-ms=3600000",
        "neighborroute.cancellation.recovery.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OutboxPublisherIntegrationTest {

    private static final String TOPIC = "orders.events.v1";

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(
                    DockerImageName.parse("apache/kafka:4.1.2")
            );

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add(
                "spring.kafka.bootstrap-servers",
                kafka::getBootstrapServers
        );
    }

    @Autowired
    private OutboxEventWriter eventWriter;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldPublishOutboxEventToKafka() throws Exception {
        UUID orderId = UUID.randomUUID();

        createTopic();

        eventWriter.appendOrderCancelled(orderId);

        assertEquals(1, countOutbox(orderId));

        publisher.publishPending();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT published_at, attempts
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                orderId
        );

        assertNotNull(row.get("published_at"));
        assertEquals(
                0,
                ((Number) row.get("attempts")).intValue()
        );

        Properties properties = new Properties();
        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()
        );
        properties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                "outbox-test-" + UUID.randomUUID()
        );
        properties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );
        properties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName()
        );
        properties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName()
        );

        ConsumerRecord<String, String> received = null;

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(properties)) {

            consumer.subscribe(List.of(TOPIC));

            long deadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(15);

            while (System.nanoTime() < deadline && received == null) {
                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(300));

                for (ConsumerRecord<String, String> record : records) {
                    if (orderId.toString().equals(record.key())) {
                        received = record;
                        break;
                    }
                }
            }
        }

        assertNotNull(received, "Kafka message was not received");
        assertEquals(orderId.toString(), received.key());
        assertTrue(received.value().contains(orderId.toString()));
        assertTrue(received.value().contains("OrderCancelled"));
    }

    @Test
    void shouldRollbackOutboxInsertOnTransactionFailure() {
        UUID orderId = UUID.randomUUID();

        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () ->
                template.executeWithoutResult(status -> {
                    eventWriter.appendOrderCancelled(orderId);
                    throw new IllegalStateException("Rollback test");
                })
        );

        assertEquals(0, countOutbox(orderId));
    }


    @Test
    @SuppressWarnings("unchecked")
    void shouldRetryFailedPublication() {
        UUID orderId = UUID.randomUUID();

        eventWriter.appendOrderCancelled(orderId);

        KafkaTemplate<String, String> kafkaMock =
                mock(KafkaTemplate.class);

        when(kafkaMock.send(
                eq(TOPIC),
                eq(orderId.toString()),
                anyString()
        )).thenReturn(
                CompletableFuture.failedFuture(
                        new RuntimeException("Kafka unavailable")
                ),
                CompletableFuture.completedFuture(null)
        );

        OutboxPublisher testPublisher = new OutboxPublisher(
                jdbcTemplate,
                kafkaMock,
                transactionManager
        );


        testPublisher.publishPending();

        Map<String, Object> failed = jdbcTemplate.queryForMap(
                """
                SELECT published_at, attempts
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                orderId
        );

        assertNull(failed.get("published_at"));
        assertEquals(
                1,
                ((Number) failed.get("attempts")).intValue()
        );


        testPublisher.publishPending();

        Map<String, Object> recovered = jdbcTemplate.queryForMap(
                """
                SELECT published_at, attempts
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                orderId
        );

        assertNotNull(recovered.get("published_at"));
        assertEquals(
                1,
                ((Number) recovered.get("attempts")).intValue()
        );

        verify(kafkaMock, times(2)).send(
                eq(TOPIC),
                eq(orderId.toString()),
                anyString()
        );
    }


    private int countOutbox(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                Integer.class,
                orderId
        );
    }

    private void createTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(
                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        kafka.getBootstrapServers()
                )
        )) {
            admin.createTopics(
                    List.of(new NewTopic(TOPIC, 1, (short) 1))
            ).all().get(10, TimeUnit.SECONDS);
        }
    }
}


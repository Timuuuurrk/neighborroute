
package com.neighborroute.orders.event;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "neighborroute.outbox.publisher.enabled=false"
})
@Testcontainers
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.AFTER_CLASS
)
class BatchDeliveredListenerIntegrationTest {

    private static final String TOPIC = "batches.events.v1";

    private static final String DLT_TOPIC = "batches.events.v1.DLT";

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

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
                    List.of(
                            new NewTopic(TOPIC, 3, (short) 1),
                            new NewTopic(DLT_TOPIC, 3, (short) 1)
                    )
            ).all().get(20, TimeUnit.SECONDS);
        }
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper jsonMapper;




    private ConsumerRecord<String, String> readFromDlt(
            UUID batchId
    ) {
        Properties properties = new Properties();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()
        );
        properties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                "dlt-test-" + UUID.randomUUID()
        );
        properties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );
        properties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );
        properties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(properties)) {

            consumer.subscribe(List.of(DLT_TOPIC));

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(30);

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
                "Message not found in DLT for batch " + batchId
        );
    }


    private UUID createConfirmedOrder() {
        UUID orderId = UUID.randomUUID();

        Long storeId = jdbcTemplate.queryForObject(
                """
                INSERT INTO stores (name, address)
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                "Kafka Test Store " + orderId,
                "Test Street 1"
        );

        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    id,
                    customer_id,
                    store_id,
                    delivery_building,
                    status,
                    created_at,
                    version
                )
                VALUES (
                    ?, ?, ?, ?,
                    'CONFIRMED',
                    CURRENT_TIMESTAMP,
                    0
                )
                """,
                orderId,
                UUID.randomUUID(),
                storeId,
                "Kafka Test Building"
        );

        return orderId;
    }

    private String getStatus(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM orders
                WHERE id = ?
                """,
                String.class,
                orderId
        );
    }

    private Long getVersion(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT version
                FROM orders
                WHERE id = ?
                """,
                Long.class,
                orderId
        );
    }

    private int countInbox(UUID eventId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM inbox_events
                WHERE event_id = ?
                """,
                Integer.class,
                eventId
        );
    }

    private void awaitCondition(BooleanSupplier condition)
            throws InterruptedException {

        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(20);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            Thread.sleep(100);
        }

        fail("Condition was not satisfied within 20 seconds");
    }


    private void createConfirmedOrderWithId(UUID orderId) {
        Long storeId = jdbcTemplate.queryForObject(
                """
                INSERT INTO stores (name, address)
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                "DLT Recovery Store " + orderId,
                "Test Street 1"
        );

        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    id,
                    customer_id,
                    store_id,
                    delivery_building,
                    status,
                    created_at,
                    version
                )
                VALUES (
                    ?, ?, ?, ?,
                    'CONFIRMED',
                    CURRENT_TIMESTAMP,
                    0
                )
                """,
                orderId,
                UUID.randomUUID(),
                storeId,
                "DLT Recovery Building"
        );
    }


    @Test
    void shouldProcessBatchDeliveredFromKafka()
            throws Exception {

        UUID order1 = createConfirmedOrder();
        UUID order2 = createConfirmedOrder();

        UUID batchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        BatchDeliveredEvent event = new BatchDeliveredEvent(
                eventId,
                "BatchDelivered",
                batchId,
                List.of(order1, order2),
                Instant.now()
        );

        String payload = jsonMapper.writeValueAsString(event);

        kafkaTemplate.send(
                TOPIC,
                batchId.toString(),
                payload
        ).get(10, TimeUnit.SECONDS);

        awaitCondition(() ->
                "DELIVERED".equals(getStatus(order1))
                        && "DELIVERED".equals(getStatus(order2))
                        && countInbox(eventId) == 1
        );

        assertEquals("DELIVERED", getStatus(order1));
        assertEquals("DELIVERED", getStatus(order2));

        assertEquals(1L, getVersion(order1));
        assertEquals(1L, getVersion(order2));

        assertEquals(1, countInbox(eventId));

        String eventType = jdbcTemplate.queryForObject(
                """
                SELECT event_type
                FROM inbox_events
                WHERE event_id = ?
                """,
                String.class,
                eventId
        );

        UUID storedBatchId = jdbcTemplate.queryForObject(
                """
                SELECT aggregate_id
                FROM inbox_events
                WHERE event_id = ?
                """,
                UUID.class,
                eventId
        );

        assertEquals("BatchDelivered", eventType);
        assertEquals(batchId, storedBatchId);
    }


    @Test
    void shouldSendFailedEventToDlt() throws Exception {

        UUID batchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        UUID missingOrderId = UUID.randomUUID();

        BatchDeliveredEvent event = new BatchDeliveredEvent(
                eventId,
                "BatchDelivered",
                batchId,
                List.of(missingOrderId),
                Instant.now()
        );

        String payload = jsonMapper.writeValueAsString(event);

        kafkaTemplate.send(
                TOPIC,
                batchId.toString(),
                payload
        ).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> failedRecord =
                readFromDlt(batchId);

        assertEquals(DLT_TOPIC, failedRecord.topic());
        assertEquals(batchId.toString(), failedRecord.key());
        assertEquals(payload, failedRecord.value());

        assertEquals(0, countInbox(eventId));
    }


    @Test
    void shouldRecoverFromDltAfterFixingError() throws Exception {
        UUID batchId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID missingOrderId = UUID.randomUUID();

        BatchDeliveredEvent event = new BatchDeliveredEvent(
                eventId,
                "BatchDelivered",
                batchId,
                List.of(missingOrderId),
                Instant.now()
        );

        String payload = jsonMapper.writeValueAsString(event);

        kafkaTemplate.send(
                TOPIC,
                batchId.toString(),
                payload
        ).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> failedRecord =
                readFromDlt(batchId);

        assertEquals(DLT_TOPIC, failedRecord.topic());
        assertEquals(batchId.toString(), failedRecord.key());
        assertEquals(payload, failedRecord.value());

        assertEquals(0, countInbox(eventId));

        createConfirmedOrderWithId(missingOrderId);

        assertEquals(
                "CONFIRMED",
                getStatus(missingOrderId)
        );

        kafkaTemplate.send(
                TOPIC,
                failedRecord.key(),
                failedRecord.value()
        ).get(10, TimeUnit.SECONDS);

        awaitCondition(() ->
                "DELIVERED".equals(getStatus(missingOrderId))
                        && countInbox(eventId) == 1
        );

        assertEquals(
                "DELIVERED",
                getStatus(missingOrderId)
        );

        assertEquals(1L, getVersion(missingOrderId));
        assertEquals(1, countInbox(eventId));

        UUID storedBatchId = jdbcTemplate.queryForObject(
                """
                SELECT aggregate_id
                FROM inbox_events
                WHERE event_id = ?
                """,
                UUID.class,
                eventId
        );

        assertEquals(batchId, storedBatchId);
    }





}

package com.neighborroute.batchservice.integration;


import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "batch.expiration.scheduler.enabled=false",
        "spring.kafka.consumer.group-id=neighborroute-inbox-it",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@Testcontainers
class OrderEventsKafkaIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldProcessEventsAndIgnoreDuplicates() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();

        String original = payload(eventId, orderId);
        String second = payload(secondEventId, orderId);

        Properties properties = new Properties();
        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()
        );
        properties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );
        properties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );
        properties.put(
                ProducerConfig.ACKS_CONFIG,
                "all"
        );

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(properties)) {

            send(producer, orderId, original);

            send(producer, orderId, original);

            send(producer, orderId, second);
        }

        long deadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (System.nanoTime() < deadline) {
            if (count(secondEventId) == 1) {
                break;
            }
            Thread.sleep(200);
        }

        assertEquals(1, count(eventId));
        assertEquals(1, count(secondEventId));
        assertEquals(
                2,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM inbox_events",
                        Integer.class
                )
        );
    }

    private void send(
            KafkaProducer<String, String> producer,
            UUID orderId,
            String payload
    ) throws Exception {
        producer.send(
                new ProducerRecord<>(
                        TOPIC,
                        orderId.toString(),
                        payload
                )
        ).get(10, TimeUnit.SECONDS);
    }

    private int count(UUID eventId) {
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

    private String payload(UUID eventId, UUID orderId) {
        return """
                {
                    "eventId": "%s",
                    "eventType": "OrderCancelled",
                    "orderId": "%s"
                }
                """.formatted(eventId, orderId);
    }
}

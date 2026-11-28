package com.neighborroute.batchservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "neighborroute.outbox.publisher.enabled=false",
        "neighborroute.cancellation.recovery.enabled=false",
        "batch.expiration.scheduler.enabled=false"
})
@Testcontainers
class BatchServiceApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void contextLoads() {
    }
}
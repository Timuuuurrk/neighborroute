package com.neighborroute.batchservice.integration;


import com.neighborroute.batchservice.cache.OpenBatchCacheInvalidator;
import com.neighborroute.batchservice.dto.OpenBatchView;
import com.neighborroute.batchservice.service.OpenBatchQueryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.cache.type=redis",
        "spring.kafka.listener.auto-startup=false",
        "batch.expiration.scheduler.enabled=false",
        "spring.data.redis.connect-timeout=500ms",
        "spring.data.redis.timeout=500ms"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisFailureIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(
                    DockerImageName.parse("redis:7-alpine")
            ).withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add(
                "spring.data.redis.port",
                () -> redis.getMappedPort(6379)
        );
    }

    @Autowired
    OpenBatchQueryService queryService;

    @Autowired
    OpenBatchCacheInvalidator invalidator;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void shouldWorkWhenRedisIsUnavailable() {
        String building = "Redis Failure " + UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO batches (
                    id, store_id, delivery_building,
                    status, created_at, expires_at
                )
                VALUES (
                    ?, 1, ?, 'OPEN',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour'
                )
                """,
                batchId,
                building
        );

        redis.stop();

        List<OpenBatchView> batches =
                queryService.findOpenBatches(1L, building);

        assertEquals(1, batches.size());
        assertEquals(batchId, batches.get(0).id());

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    UPDATE batches
                    SET status = 'EXPIRED'
                    WHERE id = ?
                    """,
                    batchId
            );

            invalidator.evictAfterCommit(1L, building);
        });

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM batches
                WHERE id = ?
                  AND status = 'EXPIRED'
                """,
                Integer.class,
                batchId
        );

        assertEquals(1, count);

        assertTrue(
                queryService.findOpenBatches(1L, building).isEmpty()
        );
    }
}

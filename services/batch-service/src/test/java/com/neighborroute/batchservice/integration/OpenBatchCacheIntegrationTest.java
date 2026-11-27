package com.neighborroute.batchservice.integration;

import java.util.List;
import java.util.UUID;

import com.neighborroute.batchservice.cache.OpenBatchCacheInvalidator;
import com.neighborroute.batchservice.dto.OpenBatchView;
import com.neighborroute.batchservice.service.OpenBatchQueryService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.cache.type=redis",
        "spring.cache.redis.time-to-live=180s",
        "spring.kafka.listener.auto-startup=false",
        "batch.expiration.scheduler.enabled=false"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenBatchCacheIntegrationTest {

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
    StringRedisTemplate redisTemplate;

    @Autowired
    CacheManager cacheManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    RedisConnectionFactory redisConnectionFactory;

    private static final Long STORE_ID = 1L;

    private String newBuilding() {
        return "Cache Test " + UUID.randomUUID();
    }

    private String cacheKey(String building) {
        return "open-batches::" + STORE_ID + ":" + building;
    }

    private boolean keyExists(String building) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(cacheKey(building))
        );
    }

    private void insertBatch(String building) {
        jdbcTemplate.update(
                """
                INSERT INTO batches (
                    id, store_id, delivery_building,
                    status, created_at, expires_at
                )
                VALUES (
                    ?, ?, ?, 'OPEN',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour'
                )
                """,
                UUID.randomUUID(),
                STORE_ID,
                building
        );
    }

    @Test
    void shouldReadFromCacheUntilEvicted() {
        String building = newBuilding();
        assertTrue(
                queryService.findOpenBatches(STORE_ID, building).isEmpty()
        );
        assertTrue(keyExists(building));

        insertBatch(building);

        assertTrue(
                queryService.findOpenBatches(STORE_ID, building).isEmpty()
        );

        cacheManager.getCache("open-batches")
                .evict(STORE_ID + ":" + building);

        List<OpenBatchView> batches =
                queryService.findOpenBatches(STORE_ID, building);

        assertEquals(1, batches.size());
        assertEquals(STORE_ID, batches.get(0).storeId());
        assertEquals(building, batches.get(0).deliveryBuilding());
        assertEquals(0L, batches.get(0).activeOrders());
    }

    @Test
    void shouldEvictCacheAfterCommit() {
        String building = newBuilding();

        queryService.findOpenBatches(STORE_ID, building);
        assertTrue(keyExists(building));

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            insertBatch(building);

            invalidator.evictAfterCommit(STORE_ID, building);

            assertTrue(keyExists(building));
        });

        assertFalse(keyExists(building));

        List<OpenBatchView> batches =
                queryService.findOpenBatches(STORE_ID, building);

        assertEquals(1, batches.size());
        assertTrue(keyExists(building));
    }

    @Test
    void shouldUseTestcontainersRedis() {
        LettuceConnectionFactory factory =
                (LettuceConnectionFactory) redisConnectionFactory;

        System.out.println(
                "TESTCONTAINERS REDIS: " +
                        redis.getHost() + ":" + redis.getMappedPort(6379)
        );

        System.out.println(
                "SPRING REDIS: " +
                        factory.getHostName() + ":" + factory.getPort()
        );

        assertEquals(
                redis.getMappedPort(6379),
                factory.getPort()
        );

        assertEquals(
                redis.getHost(),
                factory.getHostName()
        );
    }

    @Test
    void shouldNotEvictCacheAfterRollback() {
        String building = newBuilding();

        queryService.findOpenBatches(STORE_ID, building);
        assertTrue(keyExists(building));

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            insertBatch(building);

            invalidator.evictAfterCommit(STORE_ID, building);

            status.setRollbackOnly();
        });

        assertTrue(keyExists(building));

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM batches
                WHERE store_id = ?
                  AND delivery_building = ?
                """,
                Integer.class,
                STORE_ID,
                building
        );

        assertEquals(0, count);
        assertTrue(
                queryService.findOpenBatches(STORE_ID, building).isEmpty()
        );
    }
}

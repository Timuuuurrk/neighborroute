
package com.neighborroute.batchservice.integration;

import com.neighborroute.batchservice.client.OrderClient;
import com.neighborroute.batchservice.domain.Batch;
import com.neighborroute.batchservice.domain.BatchStatus;
import com.neighborroute.batchservice.dto.BatchDetailsResponse;
import com.neighborroute.batchservice.dto.CloseBatchResponse;
import com.neighborroute.batchservice.dto.DeliverBatchResponse;
import com.neighborroute.batchservice.repository.BatchRepository;
import com.neighborroute.batchservice.service.*;
import com.neighborroute.batchservice.exception.BatchNotOpenException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.neighborroute.batchservice.client.OrderDetails;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.cache.type=redis",
        "spring.cache.redis.time-to-live=180s",
        "batch.expiration.scheduler.enabled=false",
        "neighborroute.outbox.publisher.enabled=false"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BatchClosingIntegrationTest {

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
    BatchClosingService closingService;

    @Autowired
    OpenBatchQueryService queryService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    BatchService batchService;

    @MockitoBean
    OrderClient orderClient;

    @MockitoSpyBean
    BatchRepository batchRepository;

    @Autowired
    BatchDetailsService detailsService;

    @Autowired
    BatchDeliveryService deliveryService;

    @Autowired
    PlatformTransactionManager transactionManager;

    private static final Long STORE_ID = 1L;

    private UUID createBatch(String status) {
        UUID id = UUID.randomUUID();
        String building = "Close Test " + id;

        jdbcTemplate.update(
                """
                INSERT INTO batches (
                    id, store_id, delivery_building,
                    status, created_at, expires_at
                )
                VALUES (
                    ?, ?, ?, ?,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour'
                )
                """,
                id,
                STORE_ID,
                building,
                status
        );

        return id;
    }

    private String building(UUID batchId) {
        return "Close Test " + batchId;
    }

    private String getStatus(UUID batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM batches WHERE id = ?",
                String.class,
                batchId
        );
    }

    private boolean cacheExists(UUID batchId) {
        String key = "open-batches::" + STORE_ID
                + ":" + building(batchId);

        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Test
    void shouldCloseOpenBatchAndEvictCache() {
        UUID batchId = createBatch("OPEN");

        queryService.findOpenBatches(STORE_ID, building(batchId));

        assertTrue(cacheExists(batchId));

        CloseBatchResponse response =
                closingService.closeBatch(batchId);

        assertEquals(batchId, response.batchId());
        assertEquals("CLOSED", response.outcome());

        assertEquals("CLOSED", getStatus(batchId));

        assertFalse(cacheExists(batchId));

        assertTrue(
                queryService
                        .findOpenBatches(STORE_ID, building(batchId))
                        .isEmpty()
        );
    }

    @Test
    void shouldCloseIdempotently() {
        UUID batchId = createBatch("OPEN");

        CloseBatchResponse first =
                closingService.closeBatch(batchId);

        CloseBatchResponse second =
                closingService.closeBatch(batchId);

        assertEquals("CLOSED", first.outcome());
        assertEquals("ALREADY_CLOSED", second.outcome());

        assertEquals("CLOSED", getStatus(batchId));
    }

    @Test
    void shouldRejectExpiredBatch() {
        UUID batchId = createBatch("EXPIRED");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> closingService.closeBatch(batchId)
        );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );

        assertEquals("EXPIRED", getStatus(batchId));
    }

    @Test
    void shouldHandleConcurrentCloseRequests() throws Exception {
        UUID batchId = createBatch("OPEN");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<String> task = () -> {
            ready.countDown();
            start.await();
            return closingService.closeBatch(batchId).outcome();
        };

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<String> first = executor.submit(task);
            Future<String> second = executor.submit(task);

            assertTrue(ready.await(5, TimeUnit.SECONDS));

            start.countDown();

            List<String> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertEquals(
                    Set.of("CLOSED", "ALREADY_CLOSED"),
                    Set.copyOf(outcomes)
            );
        }

        assertEquals("CLOSED", getStatus(batchId));
    }


    @Test
    void shouldNotJoinBatchClosedDuringJoin() throws Exception {
        UUID oldBatchId = createBatch("OPEN");
        String address = building(oldBatchId);
        UUID orderId = UUID.randomUUID();

        OrderDetails details = mock(OrderDetails.class);

        when(details.storeId()).thenReturn(STORE_ID);
        when(details.deliveryBuilding()).thenReturn(address);
        when(details.status()).thenReturn("CONFIRMED");

        CountDownLatch secondFetchReached = new CountDownLatch(1);
        CountDownLatch continueJoin = new CountDownLatch(1);
        AtomicInteger fetchCount = new AtomicInteger();

        when(orderClient.getOrder(orderId)).thenAnswer(invocation -> {
            int currentCall = fetchCount.incrementAndGet();

            if (currentCall == 2) {
                secondFetchReached.countDown();

                if (!continueJoin.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Join timed out");
                }
            }

            return details;
        });

        try (ExecutorService executor =
                     Executors.newSingleThreadExecutor()) {

            Future<JoinBatchResult> joining = executor.submit(
                    () -> batchService.joinBatch(orderId)
            );

            try {
                assertTrue(secondFetchReached.await(
                        10, TimeUnit.SECONDS
                ));

                CloseBatchResponse closed =
                        closingService.closeBatch(oldBatchId);

                assertEquals("CLOSED", closed.outcome());
            } finally {
                continueJoin.countDown();
            }

            JoinBatchResult result =
                    joining.get(10, TimeUnit.SECONDS);

            assertEquals("CLOSED", getStatus(oldBatchId));

            assertNotEquals(oldBatchId, result.batchId());
            assertEquals("OPEN", getStatus(result.batchId()));

            Integer oldCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM batch_orders
                    WHERE batch_id = ?
                      AND order_id = ?
                      AND status = 'ACTIVE'
                    """,
                    Integer.class,
                    oldBatchId,
                    orderId
            );

            Integer newCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM batch_orders
                    WHERE batch_id = ?
                      AND order_id = ?
                      AND status = 'ACTIVE'
                    """,
                    Integer.class,
                    result.batchId(),
                    orderId
            );

            assertEquals(0, oldCount);
            assertEquals(1, newCount);
        }
    }


    @Test
    void shouldRejectJoinIfBatchClosedAfterLookup() throws Exception {
        UUID batchId = createBatch("OPEN");
        String address = building(batchId);
        UUID orderId = UUID.randomUUID();

        OrderDetails details = mock(OrderDetails.class);

        when(details.storeId()).thenReturn(STORE_ID);
        when(details.deliveryBuilding()).thenReturn(address);
        when(details.status()).thenReturn("CONFIRMED");

        when(orderClient.getOrder(orderId)).thenReturn(details);

        CountDownLatch afterLookup = new CountDownLatch(1);
        CountDownLatch resumeJoin = new CountDownLatch(1);

        doAnswer(invocation -> {
            Optional<Batch> result = batchRepository.findById(batchId);

            assertTrue(result.isPresent(), "Batch not found");

            afterLookup.countDown();

            if (!resumeJoin.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting to resume join"
                );
            }

            return result;
        }).when(batchRepository)
                .findFirstByStoreIdAndDeliveryBuildingAndStatusAndExpiresAtAfterOrderByCreatedAtAsc(
                        eq(STORE_ID),
                        eq(address),
                        eq(BatchStatus.OPEN),
                        any(Instant.class)
                );

        try (ExecutorService executor =
                     Executors.newSingleThreadExecutor()) {

            Future<JoinBatchResult> joining = executor.submit(
                    () -> batchService.joinBatch(orderId)
            );

            try {
                assertTrue(afterLookup.await(
                        10, TimeUnit.SECONDS
                ));

                CloseBatchResponse closed =
                        closingService.closeBatch(batchId);

                assertEquals("CLOSED", closed.outcome());
            } finally {
                resumeJoin.countDown();
            }

            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> joining.get(10, TimeUnit.SECONDS)
            );

            BatchNotOpenException cause = assertInstanceOf(
                    BatchNotOpenException.class,
                    exception.getCause()
            );

            assertEquals("Batch is not OPEN", cause.getMessage());

        }

        assertEquals("CLOSED", getStatus(batchId));

        Integer activeMemberships = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM batch_orders
                WHERE order_id = ?
                  AND status = 'ACTIVE'
                """,
                Integer.class,
                orderId
        );

        assertEquals(0, activeMemberships);
    }


    @Test
    void shouldReturnBatchWithActiveOrder() {
        UUID batchId = createBatch("OPEN");
        UUID orderId = UUID.randomUUID();
        String address = building(batchId);

        OrderDetails details = mock(OrderDetails.class);

        when(details.storeId()).thenReturn(STORE_ID);
        when(details.deliveryBuilding()).thenReturn(address);
        when(details.status()).thenReturn("CONFIRMED");

        when(orderClient.getOrder(orderId)).thenReturn(details);

        JoinBatchResult joined = batchService.joinBatch(orderId);

        assertEquals(batchId, joined.batchId());

        BatchDetailsResponse response =
                detailsService.getBatch(batchId);

        assertEquals(batchId, response.batchId());
        assertEquals(STORE_ID, response.storeId());
        assertEquals(address, response.deliveryBuilding());
        assertEquals("OPEN", response.status());

        assertEquals(List.of(orderId), response.activeOrderIds());

        closingService.closeBatch(batchId);

        BatchDetailsResponse closedResponse =
                detailsService.getBatch(batchId);

        assertEquals("CLOSED", closedResponse.status());

        assertEquals(
                List.of(orderId),
                closedResponse.activeOrderIds()
        );
    }


    @Test
    void shouldExcludeRemovedOrderFromBatchDetails() {
        UUID batchId = createBatch("OPEN");
        UUID orderId = UUID.randomUUID();
        String address = building(batchId);

        OrderDetails details = mock(OrderDetails.class);

        when(details.storeId()).thenReturn(STORE_ID);
        when(details.deliveryBuilding()).thenReturn(address);
        when(details.status()).thenReturn("CONFIRMED");

        when(orderClient.getOrder(orderId)).thenReturn(details);

        batchService.joinBatch(orderId);

        assertEquals(
                List.of(orderId),
                detailsService.getBatch(batchId).activeOrderIds()
        );

        batchService.leaveBatch(orderId);

        BatchDetailsResponse response =
                detailsService.getBatch(batchId);

        assertTrue(response.activeOrderIds().isEmpty());
        assertEquals("OPEN", response.status());
    }


    @Test
    void shouldReturnEmptyBatch() {
        UUID batchId = createBatch("OPEN");

        BatchDetailsResponse response =
                detailsService.getBatch(batchId);

        assertEquals(batchId, response.batchId());
        assertEquals("OPEN", response.status());
        assertTrue(response.activeOrderIds().isEmpty());
    }

    @Test
    void shouldReturnNotFoundForUnknownBatch() {
        UUID unknownId = UUID.randomUUID();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> detailsService.getBatch(unknownId)
        );

        assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getStatusCode()
        );
    }

    @Test
    void shouldDeliverClosedBatch() {
        UUID batchId = createBatch("CLOSED");

        DeliverBatchResponse response =
                deliveryService.deliverBatch(batchId);

        assertEquals(batchId, response.batchId());
        assertEquals("DELIVERED", response.outcome());

        assertEquals("DELIVERED", getStatus(batchId));
    }

    @Test
    void shouldDeliverIdempotently() {
        UUID batchId = createBatch("CLOSED");

        DeliverBatchResponse first =
                deliveryService.deliverBatch(batchId);

        DeliverBatchResponse second =
                deliveryService.deliverBatch(batchId);

        assertEquals("DELIVERED", first.outcome());
        assertEquals("ALREADY_DELIVERED", second.outcome());

        assertEquals("DELIVERED", getStatus(batchId));
    }

    @Test
    void shouldRejectDeliveryFromInvalidStatuses() {
        for (String status : List.of("OPEN", "EXPIRED")) {
            UUID batchId = createBatch(status);

            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> deliveryService.deliverBatch(batchId)
            );

            assertEquals(
                    HttpStatus.CONFLICT,
                    exception.getStatusCode()
            );

            assertEquals(status, getStatus(batchId));
        }
    }

    @Test
    void shouldReturnNotFoundWhenDeliveringUnknownBatch() {
        UUID unknownId = UUID.randomUUID();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> deliveryService.deliverBatch(unknownId)
        );

        assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getStatusCode()
        );
    }


    @Test
    void shouldHandleConcurrentDeliveryRequests() throws Exception {
        UUID batchId = createBatch("CLOSED");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<String> task = () -> {
            ready.countDown();
            start.await();

            return deliveryService
                    .deliverBatch(batchId)
                    .outcome();
        };

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<String> first = executor.submit(task);
            Future<String> second = executor.submit(task);

            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }

            List<String> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertEquals(
                    1,
                    outcomes.stream()
                            .filter("DELIVERED"::equals)
                            .count()
            );

            assertEquals(
                    1,
                    outcomes.stream()
                            .filter("ALREADY_DELIVERED"::equals)
                            .count()
            );
        }

        assertEquals("DELIVERED", getStatus(batchId));
    }

    @Test
    void shouldCreateOutboxEventWhenDelivered() {
        UUID batchId = createBatch("CLOSED");

        deliveryService.deliverBatch(batchId);

        assertEquals("DELIVERED", getStatus(batchId));

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM outbox_events
                WHERE aggregate_id = ?
                  AND event_type = 'BatchDelivered'
                  AND published_at IS NULL
                """,
                Integer.class,
                batchId
        );

        assertEquals(1, count);

        String eventType = jdbcTemplate.queryForObject(
                """
                SELECT payload ->> 'eventType'
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                String.class,
                batchId
        );

        assertEquals("BatchDelivered", eventType);

        Integer orderCount = jdbcTemplate.queryForObject(
                """
                SELECT jsonb_array_length(payload -> 'orderIds')
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                Integer.class,
                batchId
        );

        assertEquals(0, orderCount);
    }

    @Test
    void shouldNotCreateDuplicateOutboxEvent() {
        UUID batchId = createBatch("CLOSED");

        DeliverBatchResponse first =
                deliveryService.deliverBatch(batchId);

        DeliverBatchResponse second =
                deliveryService.deliverBatch(batchId);

        assertEquals("DELIVERED", first.outcome());
        assertEquals("ALREADY_DELIVERED", second.outcome());

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM outbox_events
                WHERE aggregate_id = ?
                  AND event_type = 'BatchDelivered'
                """,
                Integer.class,
                batchId
        );

        assertEquals(1, count);
    }

    @Test
    void shouldRollbackDeliveryAndOutboxTogether() {
        UUID batchId = createBatch("CLOSED");

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            deliveryService.deliverBatch(batchId);

            status.setRollbackOnly();
        });

        assertEquals("CLOSED", getStatus(batchId));

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM outbox_events
                WHERE aggregate_id = ?
                """,
                Integer.class,
                batchId
        );

        assertEquals(0, count);
    }


}


package com.neighborroute.orders.event;

import com.neighborroute.orders.domain.Order;
import com.neighborroute.orders.domain.OrderStatus;
import com.neighborroute.orders.domain.InvalidOrderStateException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "neighborroute.outbox.publisher.enabled=false"
})
@Testcontainers
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.AFTER_CLASS
)
class BatchDeliveredProcessorIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

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
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    BatchDeliveredProcessor processor;

    @Autowired
    PlatformTransactionManager transactionManager;

    @PersistenceContext
    EntityManager entityManager;

    private UUID createOrder(OrderStatus status) {
        UUID orderId = UUID.randomUUID();

        Long storeId = jdbcTemplate.queryForObject(
                """
                INSERT INTO stores (name, address)
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                "Test Store " + orderId,
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
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
                """,
                orderId,
                UUID.randomUUID(),
                storeId,
                "Test Building " + orderId,
                status.name()
        );

        return orderId;
    }

    private BatchDeliveredEvent createEvent(
            List<UUID> orderIds
    ) {
        return new BatchDeliveredEvent(
                UUID.randomUUID(),
                "BatchDelivered",
                UUID.randomUUID(),
                orderIds,
                Instant.now()
        );
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

    @Test
    void shouldDeliverMultipleOrders() {
        UUID order1 = createOrder(OrderStatus.CONFIRMED);
        UUID order2 = createOrder(OrderStatus.CONFIRMED);
        UUID order3 = createOrder(OrderStatus.CONFIRMED);

        BatchDeliveredEvent event = createEvent(
                List.of(order1, order2, order3)
        );

        processor.process(event);

        assertEquals("DELIVERED", getStatus(order1));
        assertEquals("DELIVERED", getStatus(order2));
        assertEquals("DELIVERED", getStatus(order3));

        assertEquals(1L, getVersion(order1));
        assertEquals(1L, getVersion(order2));
        assertEquals(1L, getVersion(order3));

        assertEquals(1, countInbox(event.eventId()));
    }

    @Test
    void shouldIgnoreDuplicateEvent() {
        UUID orderId = createOrder(OrderStatus.CONFIRMED);

        BatchDeliveredEvent event = createEvent(
                List.of(orderId)
        );

        processor.process(event);

        Long versionAfterFirst = getVersion(orderId);

        processor.process(event);

        assertEquals("DELIVERED", getStatus(orderId));

        assertEquals(
                versionAfterFirst,
                getVersion(orderId)
        );

        assertEquals(1, countInbox(event.eventId()));
    }

    @Test
    void shouldRollbackEverythingOnInvalidOrder() {

        List<UUID> ids = List.of(
                UUID.randomUUID(),
                UUID.randomUUID()
        ).stream().sorted().toList();

        UUID validOrderId = ids.get(0);
        UUID invalidOrderId = ids.get(1);

        createOrderWithId(
                validOrderId,
                OrderStatus.CONFIRMED
        );

        createOrderWithId(
                invalidOrderId,
                OrderStatus.CANCELLED
        );

        BatchDeliveredEvent event = createEvent(
                List.of(validOrderId, invalidOrderId)
        );

        assertThrows(
                InvalidOrderStateException.class,
                () -> processor.process(event)
        );

        assertEquals(
                "CONFIRMED",
                getStatus(validOrderId)
        );

        assertEquals(
                "CANCELLED",
                getStatus(invalidOrderId)
        );


        assertEquals(0, countInbox(event.eventId()));

        assertEquals(0L, getVersion(validOrderId));
    }

    @Test
    void shouldProcessConcurrentDuplicateOnlyOnce()
            throws Exception {

        UUID orderId = createOrder(OrderStatus.CONFIRMED);

        BatchDeliveredEvent event = createEvent(
                List.of(orderId)
        );

        ExecutorService pool =
                Executors.newFixedThreadPool(2);

        CountDownLatch start = new CountDownLatch(1);

        try {
            Callable<Void> task = () -> {
                start.await();
                processor.process(event);
                return null;
            };

            Future<Void> first = pool.submit(task);
            Future<Void> second = pool.submit(task);

            start.countDown();

            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);

            assertEquals(
                    "DELIVERED",
                    getStatus(orderId)
            );

            assertEquals(1L, getVersion(orderId));

            assertEquals(
                    1,
                    countInbox(event.eventId())
            );

        } finally {
            pool.shutdownNow();
        }
    }

    private void createOrderWithId(
            UUID orderId,
            OrderStatus status
    ) {
        Long storeId = jdbcTemplate.queryForObject(
                """
                INSERT INTO stores (name, address)
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                "Store " + orderId,
                "Test Street"
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
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
                """,
                orderId,
                UUID.randomUUID(),
                storeId,
                "Test Building " + orderId,
                status.name()
        );
    }


    @Test
    void shouldNotDeliverOrderWithPendingCancellation() {
        UUID orderId = createOrder(OrderStatus.CANCEL_PENDING);
        BatchDeliveredEvent event = createEvent(List.of(orderId));

        assertThrows(
                InvalidOrderStateException.class,
                () -> processor.process(event)
        );

        assertEquals("CANCEL_PENDING", getStatus(orderId));
        assertEquals(0L, getVersion(orderId));
        assertEquals(0, countInbox(event.eventId()));
    }

    @Test
    void shouldNotDeliverCancelledOrder() {
        UUID orderId = createOrder(OrderStatus.CANCELLED);
        BatchDeliveredEvent event = createEvent(List.of(orderId));

        assertThrows(
                InvalidOrderStateException.class,
                () -> processor.process(event)
        );

        assertEquals("CANCELLED", getStatus(orderId));
        assertEquals(0L, getVersion(orderId));
        assertEquals(0, countInbox(event.eventId()));
    }



    @Test
    void shouldRejectDeliveryWhenCancellationWinsRace()
            throws Exception {

        UUID orderId = createOrder(OrderStatus.CONFIRMED);
        BatchDeliveredEvent event = createEvent(List.of(orderId));

        CountDownLatch cancellationLocked =
                new CountDownLatch(1);

        CountDownLatch allowCancellationCommit =
                new CountDownLatch(1);

        CountDownLatch deliveryStarted =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<?> cancellation = executor.submit(() -> {
                TransactionTemplate transaction =
                        new TransactionTemplate(transactionManager);

                transaction.executeWithoutResult(status -> {
                    Order order = entityManager.find(
                            Order.class,
                            orderId,
                            LockModeType.PESSIMISTIC_WRITE
                    );

                    order.beginCancellation();
                    entityManager.flush();

                    cancellationLocked.countDown();

                    try {
                        if (!allowCancellationCommit.await(
                                10, TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "Cancellation timeout"
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });

            assertTrue(
                    cancellationLocked.await(5, TimeUnit.SECONDS)
            );

            Future<?> delivery = executor.submit(() -> {
                deliveryStarted.countDown();

                assertThrows(
                        InvalidOrderStateException.class,
                        () -> processor.process(event)
                );
            });

            assertTrue(
                    deliveryStarted.await(5, TimeUnit.SECONDS)
            );

            allowCancellationCommit.countDown();

            cancellation.get(20, TimeUnit.SECONDS);
            delivery.get(20, TimeUnit.SECONDS);

            assertEquals(
                    "CANCEL_PENDING",
                    getStatus(orderId)
            );

            assertEquals(1L, getVersion(orderId));

            assertEquals(0, countInbox(event.eventId()));

        } finally {
            allowCancellationCommit.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void shouldRejectCancellationWhenDeliveryWinsRace()
            throws Exception {

        UUID orderId = createOrder(OrderStatus.CONFIRMED);
        BatchDeliveredEvent event = createEvent(List.of(orderId));

        CountDownLatch deliveryProcessed =
                new CountDownLatch(1);

        CountDownLatch allowDeliveryCommit =
                new CountDownLatch(1);

        CountDownLatch cancellationStarted =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<?> delivery = executor.submit(() -> {

                TransactionTemplate transaction =
                        new TransactionTemplate(transactionManager);

                transaction.executeWithoutResult(status -> {

                    processor.process(event);

                    deliveryProcessed.countDown();

                    try {
                        if (!allowDeliveryCommit.await(
                                10, TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "Delivery commit timeout"
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });

            assertTrue(
                    deliveryProcessed.await(5, TimeUnit.SECONDS)
            );

            Future<?> cancellation = executor.submit(() -> {

                TransactionTemplate transaction =
                        new TransactionTemplate(transactionManager);

                cancellationStarted.countDown();

                assertThrows(
                        IllegalStateException.class,
                        () -> transaction.executeWithoutResult(status -> {

                            Order order = entityManager.find(
                                    Order.class,
                                    orderId,
                                    LockModeType.PESSIMISTIC_WRITE
                            );

                            order.beginCancellation();
                        })
                );
            });

            assertTrue(
                    cancellationStarted.await(5, TimeUnit.SECONDS)
            );

            allowDeliveryCommit.countDown();

            delivery.get(20, TimeUnit.SECONDS);
            cancellation.get(20, TimeUnit.SECONDS);

            assertEquals("DELIVERED", getStatus(orderId));

            assertEquals(1L, getVersion(orderId));

            assertEquals(1, countInbox(event.eventId()));

        } finally {
            allowDeliveryCommit.countDown();
            executor.shutdownNow();
        }
    }



}

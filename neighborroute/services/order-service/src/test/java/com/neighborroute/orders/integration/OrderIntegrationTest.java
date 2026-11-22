
package com.neighborroute.orders.integration;

import com.neighborroute.orders.domain.Order;
import com.neighborroute.orders.domain.Store;
import com.neighborroute.orders.dto.CreateOrderRequest;
import com.neighborroute.orders.dto.OrderResponse;
import com.neighborroute.orders.repository.OrderItemRepository;
import com.neighborroute.orders.repository.OrderRepository;
import com.neighborroute.orders.repository.StoreRepository;
import com.neighborroute.orders.service.OrderService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class OrderIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17");

    @Test
    void postgresShouldBeRunning() {
        assertTrue(postgres.isRunning());
    }

    @Test
    void shouldRollbackOrderItemIsInvalid() {
        Store store=storeRepository.save(
                new Store("Test Market", "Test Street 10")
        );
        long ordersBefore=orderRepository.count();
        long itemsBefore=itemRepository.count();

        CreateOrderRequest request=new CreateOrderRequest(
                UUID.randomUUID(),
                store.getId(),
                "Test Street 10",
                null,
                List.of(
                        new CreateOrderRequest.Item(
                                "Milk",
                                2,
                                new BigDecimal("120.50")
                        ),
                        new CreateOrderRequest.Item(
                                null,
                                1,
                                new BigDecimal("100.00")
                        )
                )
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> orderService.create(request)
        );

        assertEquals(ordersBefore, orderRepository.count());
        assertEquals(itemsBefore, itemRepository.count());
    }

    @Test
    void flywayShouldApplyMigrations() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1'
                  AND success = true
                """,
                Long.class
        );

        assertEquals(1L, count);
    }

    @Test
    void shouldPersistOrderAndItems() {
        Store store = storeRepository.save(
                new Store("Test Market", "Test Street 10")
        );

        CreateOrderRequest request = new CreateOrderRequest(
                UUID.randomUUID(),
                store.getId(),
                "Test Street 10",
                null,
                List.of(
                        new CreateOrderRequest.Item(
                                "Milk", 2, new BigDecimal("120.50")
                        ),
                        new CreateOrderRequest.Item(
                                "Bread", 1, new BigDecimal("85.00")
                        )
                )
        );

        OrderResponse created = orderService.create(request);

        Long ordersCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE id = ?",
                Long.class,
                created.id()
        );

        List<String> productNames = jdbcTemplate.queryForList(
                """
                SELECT product_name
                FROM order_items
                WHERE order_id = ?
                ORDER BY product_name
                """,
                String.class,
                created.id()
        );

        assertEquals(1L, ordersCount);
        assertEquals(List.of("Bread", "Milk"), productNames);

        Integer milkQuantity = jdbcTemplate.queryForObject(
                """
                SELECT quantity
                FROM order_items
                WHERE order_id = ? AND product_name = ?
                """,
                Integer.class,
                created.id(),
                "Milk"
        );

        BigDecimal milkPrice = jdbcTemplate.queryForObject(
                """
                SELECT unit_price
                FROM order_items
                WHERE order_id = ? AND product_name = ?
                """,
                BigDecimal.class,
                created.id(),
                "Milk"
        );

        assertEquals(2, milkQuantity);
        assertEquals(new BigDecimal("120.50"), milkPrice);

        Integer breadQuantity = jdbcTemplate.queryForObject(
                """
                SELECT quantity
                FROM order_items
                WHERE order_id = ? AND product_name = ?
                """,
                Integer.class,
                created.id(),
                "Bread"
        );

        BigDecimal breadPrice = jdbcTemplate.queryForObject(
                """
                SELECT unit_price
                FROM order_items
                WHERE order_id = ? AND product_name = ?
                """,
                BigDecimal.class,
                created.id(),
                "Bread"
        );

        assertEquals(1, breadQuantity);
        assertEquals(new BigDecimal("85.00"), breadPrice);

        BigDecimal databaseTotal = jdbcTemplate.queryForObject(
                """
                SELECT SUM(quantity * unit_price)
                FROM order_items
                WHERE order_id = ?
                """,
                BigDecimal.class,
                created.id()
        );

        assertNotNull(databaseTotal);

        assertEquals(
                0,
                new BigDecimal("326.00").compareTo(databaseTotal)
        );

        assertEquals(
                0,
                created.totalPrice().compareTo(databaseTotal)
        );
    }



    @Test
    void shouldPersistOrderConfirmation() {
        Store store = storeRepository.save(
                new Store("Test Market", "Test Street 20")
        );

        CreateOrderRequest request = new CreateOrderRequest(
                UUID.randomUUID(),
                store.getId(),
                "Test Street 20",
                null,
                List.of(
                        new CreateOrderRequest.Item(
                                "Bread",
                                1,
                                new BigDecimal("85.00")
                        )
                )
        );

        OrderResponse created = orderService.create(request);

        orderService.confirm(created.id());

        String status = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM orders
                WHERE id = ?
                """,
                String.class,
                created.id()
        );

        assertEquals("CONFIRMED", status);
    }


    @Test
    void shouldIncreaseVersionAfterConfirmation() {
        Store store = storeRepository.save(
                new Store("Version Market", "Test Street 50")
        );

        CreateOrderRequest request = new CreateOrderRequest(
                UUID.randomUUID(),
                store.getId(),
                "Test Street 50",
                null,
                List.of(
                        new CreateOrderRequest.Item(
                                "Milk", 1, new BigDecimal("100.00")
                        )
                )
        );

        OrderResponse created = orderService.create(request);

        Long versionBefore = jdbcTemplate.queryForObject(
                "SELECT version FROM orders WHERE id = ?",
                Long.class,
                created.id()
        );

        orderService.confirm(created.id());

        Long versionAfter = jdbcTemplate.queryForObject(
                "SELECT version FROM orders WHERE id = ?",
                Long.class,
                created.id()
        );

        assertEquals(0L, versionBefore);
        assertEquals(1L, versionAfter);
    }


    @Test
    void shouldPreventConcurrentOrderUpdates() throws Exception {
        Store store = storeRepository.save(
                new Store("Concurrent Market", "Test Street 30")
        );

        CreateOrderRequest request = new CreateOrderRequest(
                UUID.randomUUID(),
                store.getId(),
                "Test Street 30",
                null,
                List.of(
                        new CreateOrderRequest.Item(
                                "Milk", 1, new BigDecimal("100.00")
                        )
                )
        );

        UUID orderId = orderService.create(request).id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch allowUpdates = new CountDownLatch(1);

        try {
            Future<RuntimeException> confirmFuture = executor.submit(() -> {
                try {
                    changeStatusInTransaction(
                            orderId, true, bothRead, allowUpdates
                    );
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            });

            Future<RuntimeException> cancelFuture = executor.submit(() -> {
                try {
                    changeStatusInTransaction(
                            orderId, false, bothRead, allowUpdates
                    );
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            });

            assertTrue(bothRead.await(10, TimeUnit.SECONDS),
                    "Both transactions should read the order");

            allowUpdates.countDown();

            RuntimeException confirmError =
                    confirmFuture.get(10, TimeUnit.SECONDS);

            RuntimeException cancelError =
                    cancelFuture.get(10, TimeUnit.SECONDS);

            assertTrue((confirmError == null) != (cancelError == null));

            RuntimeException failure =
                    confirmError != null ? confirmError : cancelError;

            assertInstanceOf(
                    OptimisticLockingFailureException.class,
                    failure
            );

            String actualStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM orders WHERE id = ?",
                    String.class,
                    orderId
            );

            Long actualVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM orders WHERE id = ?",
                    Long.class,
                    orderId
            );

            String expectedStatus =
                    confirmError == null ? "CONFIRMED" : "CANCELLED";

            assertEquals(expectedStatus, actualStatus);
            assertEquals(1L, actualVersion);
        } finally {
            allowUpdates.countDown();
            executor.shutdownNow();
        }
    }




    private void changeStatusInTransaction(
            UUID orderId,
            boolean confirm,
            CountDownLatch bothRead,
            CountDownLatch allowUpdates
    ) {
        transactionTemplate.executeWithoutResult(tx -> {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow();
            bothRead.countDown();
            try {
                if (!allowUpdates.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting for updates"
                    );
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting", e
                );
            }

            if (confirm) {
                order.confirm();
            } else {
                order.cancel();
            }
        });
    }




    @Autowired
    private OrderService orderService;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository itemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;
}

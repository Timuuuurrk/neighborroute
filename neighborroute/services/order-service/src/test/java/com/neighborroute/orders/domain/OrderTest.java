package com.neighborroute.orders.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderTest {
    private Order createOrder() {
        Store store=new Store("Demo market", "Main Street 10");
        return new Order(UUID.randomUUID(), store, "Main Street 10", "Entrance 2");
    }

    @Test
    void newOrderShouldHaveCreatedStatus() {
        Order order=createOrder();
        assertEquals(OrderStatus.CREATED, order.getStatus());
    }

    @Test
    void createdOrderCanBeConfirmed() {
        Order order=createOrder();
        order.confirm();
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
    }

    @Test
    void createdOrderCanBeCancelled() {
        Order order=createOrder();
        order.cancel();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void confirmedOrderCannotBeCancelled() {
        Order order=createOrder();
        order.confirm();
        assertThrows(InvalidOrderStateException.class, order::cancel);

        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
    }

    @Test
    void cancelledOrderCannotBeConfirmed() {
        Order order=createOrder();
        order.cancel();
        assertThrows(InvalidOrderStateException.class, order::confirm);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void confirmedOrderCannotBeConfirmedAgain() {
        Order order=createOrder();
        order.confirm();
        assertThrows(InvalidOrderStateException.class, order::confirm);
    }
}

package com.neighborroute.orders.service;

import com.neighborroute.orders.domain.*;
import com.neighborroute.orders.dto.OrderResponse;
import com.neighborroute.orders.repository.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository itemRepository;

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void confirmShouldChangeOrderStatus() {
        UUID orderId=UUID.randomUUID();
        Order order=new Order(UUID.randomUUID(), new Store("Demo market", "Main Street 10"),
                "Main Street 10", "Entrance 2");
        when(orderRepository.findById(orderId))
                .thenReturn(Optional.of(order));
        when(itemRepository.findByOrder_Id(orderId))
                .thenReturn(List.of());
        OrderResponse response = orderService.confirm(orderId);

        assertEquals(OrderStatus.CONFIRMED, response.status());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());

        verify(orderRepository).findById(orderId);
        verify(itemRepository).findByOrder_Id(orderId);
    }

    @Test
    void confirmShouldReturnNotFoundForUnknownOrder() {
        UUID orderId=UUID.randomUUID();
        when(orderRepository.findById(orderId))
                .thenReturn(Optional.empty());

        ResponseStatusException exception=assertThrows(
                ResponseStatusException.class,
                () -> orderService.confirm(orderId)
        );

        assertEquals(HttpStatus.NOT_FOUND.value(),
                exception.getStatusCode().value());

        verify(itemRepository, never())
                .findByOrder_Id(orderId);
    }
}

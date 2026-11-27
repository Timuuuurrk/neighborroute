package com.neighborroute.orders.service;


import com.neighborroute.orders.domain.Order;
import com.neighborroute.orders.domain.OrderStatus;
import com.neighborroute.orders.outbox.OutboxEventWriter;
import com.neighborroute.orders.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class OrderCancellationTransactions {

    private final OrderRepository orderRepository;

    private final OutboxEventWriter outboxEventWriter;

    public OrderCancellationTransactions(
            OrderRepository orderRepository, OutboxEventWriter outboxEventWriter
    ) {
        this.orderRepository = orderRepository;
        this.outboxEventWriter=outboxEventWriter;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean begin(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Заказ не найден: " + orderId
                ));

        return switch (order.getStatus()) {
            case CREATED -> {
                order.cancel();
                outboxEventWriter.appendOrderCancelled(orderId);
                yield false;
            }
            case CONFIRMED -> {
                order.beginCancellation();
                yield true;
            }
            case CANCEL_PENDING -> true;
            case CANCELLED -> false;
            case DELIVERED -> false;
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow();

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }

        order.completeCancellation();
        outboxEventWriter.appendOrderCancelled(orderId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reject(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow();

        if (order.getStatus() == OrderStatus.CONFIRMED) {
            return;
        }

        order.rejectCancellation();
    }
}
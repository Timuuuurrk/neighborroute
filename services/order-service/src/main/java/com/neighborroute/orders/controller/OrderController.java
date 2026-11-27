package com.neighborroute.orders.controller;

import com.neighborroute.orders.dto.CreateOrderRequest;
import com.neighborroute.orders.dto.OrderResponse;
import com.neighborroute.orders.service.OrderCancellationCoordinator;
import com.neighborroute.orders.service.OrderService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;


@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    private final OrderCancellationCoordinator cancellationCoordinator;

    public OrderController(OrderService orderService, OrderCancellationCoordinator cancellationCoordinator) {
        this.orderService=orderService;
        this.cancellationCoordinator=cancellationCoordinator;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request
    ) {
        OrderResponse response=orderService.create(request);
        URI location=URI.create("/api/v1/orders"+response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable UUID id) {
        return orderService.getById(id);
    }

    @PostMapping("/{id}/confirm")
    public OrderResponse confirm(@PathVariable UUID id) {
        return orderService.confirm(id);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable UUID id) {
         cancellationCoordinator.cancel(id);
    }


}

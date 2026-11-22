package com.neighborroute.orders.dto;

import com.neighborroute.orders.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;


public record OrderResponse (
        UUID id,
        UUID customerId,
        Long storeId,
        String deliveryBuilding,
        String deliveryDetails,
        OrderStatus status,
        Instant createdAt,
        List<Item> items,
        BigDecimal totalPrice
) {
    public record Item (
            Long id,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ){}
}

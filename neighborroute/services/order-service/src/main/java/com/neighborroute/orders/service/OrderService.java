package com.neighborroute.orders.service;

import com.neighborroute.orders.domain.*;
import com.neighborroute.orders.repository.*;
import com.neighborroute.orders.dto.*;


import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    private final StoreRepository storeRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository itemRepository;

    public OrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                        StoreRepository storeRepository) {
        this.orderRepository=orderRepository;
        this.itemRepository=orderItemRepository;
        this.storeRepository=storeRepository;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Store store=storeRepository.findById(request.storeId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Магазин не найден"
                ));

        Order order=new Order(request.customerId(),
                store, request.deliveryBuilding(),
                request.deliveryDetails());
        orderRepository.save(order);
        List<OrderItem> items=request.items().stream()
                .map(item -> new OrderItem(
                        order, item.productName(), item.quantity(),
                        item.unitPrice()
                )).toList();
        itemRepository.saveAll(items);

        return toResponse(order, items);
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(UUID id) {
        Order order=orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Заказ не найден"
                ));
        List<OrderItem> items=itemRepository.findByOrder_Id(id);
        return toResponse(order, items);
    }

    @Transactional
    public OrderResponse confirm(UUID id) {
        Order order=findOrder(id);
        order.confirm();
        List<OrderItem> items= itemRepository.findByOrder_Id(id);

        return toResponse(order, items);
    }

    @Transactional
    public OrderResponse cancel(UUID id) {
        Order order=findOrder(id);
        order.cancel();
        List<OrderItem> items=itemRepository.findByOrder_Id(id);
        return toResponse(order, items);
    }

    private Order findOrder(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Заказ не найден"
                ));
    }

    private OrderResponse toResponse(Order order, List<OrderItem> items) {
        List<OrderResponse.Item> responseItems=items.stream()
                .map(item -> new OrderResponse.Item(
                        item.getId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getUnitPrice().multiply(
                                BigDecimal.valueOf(item.getQuantity())
                        )
                )).toList();
        BigDecimal totalPrice=responseItems.stream()
                .map(OrderResponse.Item::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderResponse(
                order.getId(), order.getCustomerId(),
                order.getStore().getId(),
                order.getDeliveryBuilding(),
                order.getDeliveryDetails(),
                order.getStatus(),
                order.getCreatedAt(),
                responseItems, totalPrice
        );
    }


}


package com.neighborroute.telegrambot.client;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderServiceClient {

    private final RestClient restClient;

    public OrderServiceClient(
            @Qualifier("orderRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    public UUID createOrder(
            UUID customerId,
            String building,
            String details,
            String productName,
            int quantity,
            BigDecimal unitPrice
    ) {
        CreateOrderRequest request = new CreateOrderRequest(
                customerId,
                1L,
                building,
                details,
                List.of(new OrderItemRequest(
                        productName,
                        quantity,
                        unitPrice
                ))
        );

        Map<?, ?> response = restClient.post()
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException(
                    "Order Service returned empty response"
            );
        }

        Object id = response.get("id");

        if (id == null) {
            id = response.get("orderId");
        }

        if (id == null) {
            throw new IllegalStateException(
                    "Order Service returned no order ID"
            );
        }

        return UUID.fromString(id.toString());
    }

    public void confirmOrder(UUID orderId) {
        restClient.post()
                .uri("/{orderId}/confirm", orderId)
                .retrieve()
                .toBodilessEntity();
    }

    public record CreateOrderRequest(
            UUID customerId,
            Long storeId,
            String deliveryBuilding,
            String deliveryDetails,
            List<OrderItemRequest> items
    ) {
    }

    public record OrderItemRequest(
            String productName,
            int quantity,
            BigDecimal unitPrice
    ) {
    }

    public String getOrderStatus(UUID orderId) {
        Map<?, ?> response = restClient.get()
                .uri("/{orderId}", orderId)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("status") == null) {
            throw new IllegalStateException(
                    "Order Service returned no status"
            );
        }

        return response.get("status").toString();
    }


    public void cancelOrder(UUID orderId) {
        restClient.post()
                .uri("/{orderId}/cancel", orderId)
                .retrieve()
                .toBodilessEntity();
    }

}

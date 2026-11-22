
package com.neighborroute.batchservice.client;

import com.neighborroute.batchservice.exception.OrderNotFoundException;
import com.neighborroute.batchservice.exception.OrderServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.UUID;

@Component
public class OrderClient {

    private final RestClient restClient;

    public OrderClient(
            RestClient.Builder builder,
            @Value("${app.clients.order-service.url}")
            String baseUrl
    ) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }


    public OrderDetails getOrder(UUID orderId) {
        try {
            OrderDetails result = restClient.get()
                    .uri("/api/v1/orders/{id}", orderId)
                    .retrieve()
                    .body(OrderDetails.class);

            return Objects.requireNonNull(
                    result,
                    "Order Service returned an empty response"
            );

        } catch (HttpClientErrorException.NotFound ex) {
            throw new OrderNotFoundException(orderId);
        } catch (ResourceAccessException
                 | HttpServerErrorException ex) {
            throw new OrderServiceUnavailableException("Cannot retrieve order from Order Service", ex);
        }
    }

}

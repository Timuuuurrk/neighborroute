
package com.neighborroute.telegrambot.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OrderServiceClientTest {

    @Test
    void shouldCreateAndConfirmOrder() {
        String baseUrl =
                "http://localhost:8080/api/v1/orders";

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl);

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        OrderServiceClient client =
                new OrderServiceClient(builder.build());

        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        server.expect(requestTo(baseUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.customerId")
                        .value(customerId.toString()))
                .andExpect(jsonPath("$.storeId").value(1))
                .andExpect(jsonPath("$.deliveryBuilding")
                        .value("Main Street 10"))
                .andExpect(jsonPath("$.items[0].productName")
                        .value("Milk"))
                .andExpect(jsonPath("$.items[0].quantity")
                        .value(2))
                .andExpect(jsonPath("$.items[0].unitPrice")
                        .value(120.50))
                .andRespond(withSuccess(
                        "{\"id\":\"" + orderId + "\"}",
                        MediaType.APPLICATION_JSON
                ));

        server.expect(requestTo(
                        baseUrl + "/" + orderId + "/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        UUID createdId = client.createOrder(
                customerId,
                "Main Street 10",
                "Entrance 2",
                "Milk",
                2,
                new BigDecimal("120.50")
        );

        assertEquals(orderId, createdId);

        client.confirmOrder(createdId);

        server.verify();
    }


    @Test
    void shouldGetOrderStatus() {
        String baseUrl =
                "http://localhost:8080/api/v1/orders";

        RestClient.Builder builder =
                RestClient.builder().baseUrl(baseUrl);

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        OrderServiceClient client =
                new OrderServiceClient(builder.build());

        UUID orderId = UUID.randomUUID();

        server.expect(requestTo(baseUrl + "/" + orderId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "id": "%s",
                          "status": "CONFIRMED"
                        }
                        """.formatted(orderId),
                        MediaType.APPLICATION_JSON
                ));

        String status = client.getOrderStatus(orderId);

        assertEquals("CONFIRMED", status);

        server.verify();
    }


    @Test
    void shouldSendCancelOrderRequest() {
        String baseUrl =
                "http://localhost:8080/api/v1/orders";

        RestClient.Builder builder =
                RestClient.builder().baseUrl(baseUrl);

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        OrderServiceClient client =
                new OrderServiceClient(builder.build());

        UUID orderId = UUID.randomUUID();

        server.expect(requestTo(
                        baseUrl + "/" + orderId + "/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        client.cancelOrder(orderId);

        server.verify();
    }


}

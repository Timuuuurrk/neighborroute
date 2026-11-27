
package com.neighborroute.batchservice.client;

import com.neighborroute.batchservice.exception.OrderNotFoundException;
import com.neighborroute.batchservice.exception.OrderServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(OrderClient.class)
@TestPropertySource(properties = {
        "app.clients.order-service.url=http://localhost:8080"
})
class OrderClientTest {

    @Autowired
    private OrderClient orderClient;

    @Autowired
    private MockRestServiceServer server;

    @Test
    void shouldRetrieveOrder() {
        UUID orderId = UUID.randomUUID();
        String response = """
                {
                    "id": "%s",
                    "storeId": 1,
                    "deliveryBuilding": "Lenina 10",
                    "status": "CREATED",
                    "items": []
                }
                """.formatted(orderId);
        server.expect(requestTo(
                        "http://localhost:8080/api/v1/orders/" + orderId
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        response,
                        MediaType.APPLICATION_JSON
                ));
        OrderDetails order = orderClient.getOrder(orderId);
        assertEquals(orderId, order.id());
        assertEquals(1L, order.storeId());
        assertEquals("Lenina 10", order.deliveryBuilding());
        assertEquals("CREATED", order.status());
        server.verify();
    }


    @Test
    void shouldThrowOrderNotFoundWhen404() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo(
                        "http://localhost:8080/api/v1/orders/" + orderId
                ))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThrows(
                OrderNotFoundException.class,
                () -> orderClient.getOrder(orderId)
        );
        server.verify();
    }


    @Test
    void shouldThrowUnavailableWhenConnectionFails() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo(
                        "http://localhost:8080/api/v1/orders/" + orderId
                ))
                .andRespond(withException(
                        new IOException("Connection refused")
                ));

        OrderServiceUnavailableException ex = assertThrows(
                OrderServiceUnavailableException.class,
                () -> orderClient.getOrder(orderId)
        );
        assertInstanceOf(
                ResourceAccessException.class,
                ex.getCause()
        );
        server.verify();
    }

    @Test
    void shouldThrowWhenResponseBodyIsEmpty() {
        UUID orderId = UUID.randomUUID();
        server.expect(requestTo("http://localhost:8080/api/v1/orders/" + orderId))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> orderClient.getOrder(orderId));
        assertEquals("Order Service returned an empty response", ex.getMessage());
        server.verify();
    }

}

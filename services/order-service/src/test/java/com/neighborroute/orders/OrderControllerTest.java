
package com.neighborroute.orders;

import com.neighborroute.orders.controller.OrderController;
import com.neighborroute.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.neighborroute.orders.service.OrderCancellationCoordinator;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private OrderCancellationCoordinator cancellationCoordinator;

    @Test
    void shouldReturn409OnOptimisticLockingConflict() throws Exception {
        UUID orderId = UUID.randomUUID();

        when(orderService.confirm(orderId))
                .thenThrow(new OptimisticLockingFailureException(
                        ""
                ));

        mockMvc.perform(
                        post("/api/v1/orders/{id}/confirm", orderId)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title")
                        .value("Параллельное изменение заказа"));
    }
}

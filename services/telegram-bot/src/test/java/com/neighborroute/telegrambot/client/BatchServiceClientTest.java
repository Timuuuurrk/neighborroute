
package com.neighborroute.telegrambot.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BatchServiceClientTest {

    @Test
    void shouldJoinBatchAndReturnBatchId() {
        String baseUrl =
                "http://127.0.0.1:8081/api/v1/batches";

        RestClient.Builder builder =
                RestClient.builder().baseUrl(baseUrl);

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        BatchServiceClient client =
                new BatchServiceClient(builder.build());

        UUID orderId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        server.expect(requestTo(baseUrl + "/join"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.orderId")
                        .value(orderId.toString()))
                .andRespond(withSuccess(
                        "{\"batchId\":\"" + batchId + "\"}",
                        MediaType.APPLICATION_JSON
                ));

        UUID returnedBatchId =
                client.joinBatch(orderId);

        assertEquals(batchId, returnedBatchId);

        server.verify();
    }
}

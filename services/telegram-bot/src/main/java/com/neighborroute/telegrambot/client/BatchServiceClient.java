
package com.neighborroute.telegrambot.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Service
public class BatchServiceClient {

    private final RestClient restClient;

    public BatchServiceClient(
            @Qualifier("batchRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    public UUID joinBatch(UUID orderId) {
        JoinBatchRequest request =
                new JoinBatchRequest(orderId);

        JoinBatchResponse response = restClient.post()
                .uri("/join")
                .body(request)
                .retrieve()
                .body(JoinBatchResponse.class);

        if (response == null || response.batchId() == null) {
            throw new IllegalStateException(
                    "Batch Service returned no batch ID"
            );
        }

        return response.batchId();
    }

    public record JoinBatchRequest(UUID orderId) {
    }

    public record JoinBatchResponse(UUID batchId) {
    }
}

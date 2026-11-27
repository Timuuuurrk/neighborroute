package com.neighborroute.orders.client;


import com.neighborroute.orders.domain.BatchCancellationConflictException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class BatchClient {

    private final RestClient restClient;

    public BatchClient(
            @Qualifier("batchRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    public void removeOrderFromBatch(UUID orderId) {
        try {
            restClient.delete()
                    .uri(
                            "/api/v1/batches/orders/{orderId}",
                            orderId
                    )
                    .retrieve()
                    .toBodilessEntity();

        } catch (HttpClientErrorException.Conflict ex) {
            throw new BatchCancellationConflictException(
                    "Cannot cancel order: batch is already closed"
            );
        }
    }
}

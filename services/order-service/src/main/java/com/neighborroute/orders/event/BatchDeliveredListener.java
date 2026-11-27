
package com.neighborroute.orders.event;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
public class BatchDeliveredListener {

    private final JsonMapper jsonMapper;
    private final BatchDeliveredProcessor processor;

    public BatchDeliveredListener(
            JsonMapper jsonMapper,
            BatchDeliveredProcessor processor
    ) {
        this.jsonMapper = jsonMapper;
        this.processor = processor;
    }

    @KafkaListener(
            topics = "batches.events.v1",
            groupId = "neighborroute-order-delivery-v1"
    )
    public void onMessage(String payload) {

        BatchDeliveredEvent event = jsonMapper.readValue(
                payload,
                BatchDeliveredEvent.class
        );

        processor.process(event);
    }
}

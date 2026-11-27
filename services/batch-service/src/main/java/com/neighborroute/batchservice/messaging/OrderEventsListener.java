package com.neighborroute.batchservice.messaging;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OrderEventsListener {

    private static final Logger log =
            LoggerFactory.getLogger(OrderEventsListener.class);

    private final JsonMapper jsonMapper;
    private final OrderCancelledEventProcessor processor;

    public OrderEventsListener(
            JsonMapper jsonMapper,
            OrderCancelledEventProcessor processor
    ) {
        this.jsonMapper = jsonMapper;
        this.processor = processor;
    }

    @KafkaListener(topics = "orders.events.v1")
    public void onMessage(String payload) {
        JsonNode json = jsonMapper.readTree(payload);

        if (!"OrderCancelled".equals(
                json.path("eventType").asText()
        )) {
            return;
        }

        OrderCancelledEvent event =
                jsonMapper.readValue(
                        payload,
                        OrderCancelledEvent.class
                );

        boolean inserted = processor.process(event, payload);

        if (inserted) {
            log.info(
                    "Processed OrderCancelled event={}, order={}",
                    event.eventId(),
                    event.orderId()
            );
        } else {
            log.info(
                    "Duplicate event ignored: {}",
                    event.eventId()
            );
        }
    }
}


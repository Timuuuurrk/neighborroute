package com.neighborroute.batchservice.controller;

import com.neighborroute.batchservice.dto.DeliverBatchResponse;
import com.neighborroute.batchservice.service.BatchDeliveryService;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchDeliveryController {

    private final BatchDeliveryService deliveryService;

    public BatchDeliveryController(
            BatchDeliveryService deliveryService
    ) {
        this.deliveryService = deliveryService;
    }

    @PostMapping("/{batchId}/deliver")
    public DeliverBatchResponse deliver(
            @PathVariable UUID batchId
    ) {
        return deliveryService.deliverBatch(batchId);
    }
}
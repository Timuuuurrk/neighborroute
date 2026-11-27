package com.neighborroute.batchservice.controller;

import com.neighborroute.batchservice.dto.CloseBatchResponse;
import com.neighborroute.batchservice.service.BatchClosingService;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchClosingController {

    private final BatchClosingService closingService;

    public BatchClosingController(BatchClosingService closingService) {
        this.closingService = closingService;
    }

    @PostMapping("/{batchId}/close")
    public CloseBatchResponse close(@PathVariable UUID batchId) {
        return closingService.closeBatch(batchId);
    }
}

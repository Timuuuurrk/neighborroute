package com.neighborroute.batchservice.controller;

import com.neighborroute.batchservice.dto.BatchDetailsResponse;
import com.neighborroute.batchservice.service.BatchDetailsService;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchDetailsController {

    private final BatchDetailsService detailsService;

    public BatchDetailsController(BatchDetailsService detailsService) {
        this.detailsService = detailsService;
    }

    @GetMapping("/{batchId}")
    public BatchDetailsResponse getBatch(
            @PathVariable UUID batchId
    ) {
        return detailsService.getBatch(batchId);
    }
}
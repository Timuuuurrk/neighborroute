package com.neighborroute.batchservice.controller;

import com.neighborroute.batchservice.dto.OpenBatchView;
import com.neighborroute.batchservice.service.OpenBatchQueryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/batches")
public class OpenBatchQueryController {

    private final OpenBatchQueryService queryService;

    public OpenBatchQueryController(OpenBatchQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/open")
    public List<OpenBatchView> findOpenBatches(
            @RequestParam Long storeId,
            @RequestParam String deliveryBuilding
    ) {
        return queryService.findOpenBatches(
                storeId,
                deliveryBuilding
        );
    }
}
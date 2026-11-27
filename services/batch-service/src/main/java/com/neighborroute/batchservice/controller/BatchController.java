
package com.neighborroute.batchservice.controller;

import com.neighborroute.batchservice.dto.JoinBatchRequest;
import com.neighborroute.batchservice.dto.JoinBatchResponse;
import com.neighborroute.batchservice.service.BatchService;
import com.neighborroute.batchservice.service.JoinBatchOutcome;
import com.neighborroute.batchservice.service.JoinBatchResult;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }


    @PostMapping("/join")
    public ResponseEntity<JoinBatchResponse> joinBatch(
            @Valid @RequestBody JoinBatchRequest request
    ) {
        JoinBatchResult result =
                batchService.joinBatch(request.orderId());

        JoinBatchResponse response = new JoinBatchResponse(
                result.batchId(),
                result.outcome()
        );

        HttpStatus status =
                result.outcome() == JoinBatchOutcome.CREATED
                        ? HttpStatus.CREATED
                        : HttpStatus.OK;

        return ResponseEntity.status(status).body(response);
    }

    @DeleteMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveBatch(@PathVariable UUID orderId) {
        batchService.leaveBatch(orderId);
    }

}

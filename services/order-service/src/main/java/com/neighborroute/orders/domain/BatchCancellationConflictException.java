package com.neighborroute.orders.domain;

public class BatchCancellationConflictException
        extends RuntimeException {
    public BatchCancellationConflictException(String message) {
        super(message);
    }
}

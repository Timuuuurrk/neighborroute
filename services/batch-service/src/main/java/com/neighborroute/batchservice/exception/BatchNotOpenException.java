package com.neighborroute.batchservice.exception;

import java.util.UUID;

public class BatchNotOpenException extends RuntimeException {
    public BatchNotOpenException(String message) {
        super(message);
    }
}

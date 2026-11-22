package com.neighborroute.batchservice.exception;

public class OrderServiceUnavailableException extends RuntimeException {
    public OrderServiceUnavailableException(
            String message, Throwable cause
    ) {
        super(message, cause);
    }
}
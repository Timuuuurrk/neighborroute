package com.neighborroute.batchservice.exception;

import java.util.UUID;

public class OrderNotConfirmedException extends RuntimeException {

    public OrderNotConfirmedException(UUID orderId) {
        super("Заказ " + orderId + " не подтверждён!");
    }
}
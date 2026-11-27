
package com.neighborroute.batchservice.exception;

import java.util.UUID;

public class OrderAlreadyInBatchException extends RuntimeException {

    public OrderAlreadyInBatchException(UUID orderId) {
        super("Заказ " + orderId + " уже находится в активной сессии");
    }
}

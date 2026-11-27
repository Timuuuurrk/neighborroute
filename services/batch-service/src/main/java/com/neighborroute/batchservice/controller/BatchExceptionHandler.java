
package com.neighborroute.batchservice.controller;

import com.neighborroute.batchservice.exception.*;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class BatchExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(
            OrderNotFoundException ex
    ) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
    }

    @ExceptionHandler(OrderNotConfirmedException.class)
    public ProblemDetail handleOrderNotConfirmed(
            OrderNotConfirmedException ex
    ) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
    }

    @ExceptionHandler(OrderServiceUnavailableException.class)
    public ProblemDetail handleOrderServiceUnavailable(
            OrderServiceUnavailableException ex
    ) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Сервис заказов временно недоступен"
        );
    }

    @ExceptionHandler({
            BatchNotOpenException.class,
            BatchMembershipChangedException.class
    })
    public ProblemDetail handleLeaveConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
    }
}

package com.neighborroute.orders.controller;

import com.neighborroute.orders.domain.InvalidOrderStateException;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InvalidOrderStateException.class)
    public ProblemDetail handleInvalidOrderState(
            InvalidOrderStateException exception
    ) {
        ProblemDetail problem=ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,exception.getMessage()
        );
        problem.setTitle("Неправильное состояние заказа");
        return problem;
    }



    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLocking(
            OptimisticLockingFailureException ex
    ) {
        ProblemDetail problem = ProblemDetail.forStatus(
                HttpStatus.CONFLICT
        );

        problem.setTitle("Параллельное изменение заказа");
        problem.setDetail(
                "Заказ был изменен другим запросом. " +
                        "Перезагрузите заказ и попробуйте снова."
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(problem);
    }

}

package com.neighborroute.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
    @NotNull
    UUID customerId,

    @NotNull
    Long storeId,

    @NotBlank
    @Size(max=250)
    String deliveryBuilding,

    @Size(max=250)
    String deliveryDetails,

    @NotEmpty
    @Valid
    List<Item> items
) {
    public record Item(

            @NotBlank
            @Size(max=250)
            String productName,

            @Positive
            int quantity,

            @NotNull
            @DecimalMin(("0.00"))
            @Digits(integer =10,fraction=2)
            BigDecimal unitPrice
    ){}
}

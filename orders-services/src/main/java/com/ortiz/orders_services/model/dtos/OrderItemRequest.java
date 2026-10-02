package com.ortiz.orders_services.model.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderItemRequest {

    @NotBlank
    private String sku;

    @NotNull
    @Positive
    private Double price;

    @NotNull
    @Positive
    private Long quantity;
}
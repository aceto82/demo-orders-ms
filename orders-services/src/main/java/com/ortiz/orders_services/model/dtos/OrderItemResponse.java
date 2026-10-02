package com.ortiz.orders_services.model.dtos;

public record OrderItemResponse(Long id, String sku, Double price, Long quantity) {
}

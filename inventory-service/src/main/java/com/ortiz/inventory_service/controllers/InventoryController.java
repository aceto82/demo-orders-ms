package com.ortiz.inventory_service.controllers;

import com.ortiz.inventory_service.model.dtos.BaseResponse;
import com.ortiz.inventory_service.model.dtos.OrderItemRequest;
import com.ortiz.inventory_service.services.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{sku}")
    public boolean isInStock(@PathVariable String sku) {
        return inventoryService.isInStock(sku);
    }

    @PostMapping("/in-stock")
    public BaseResponse areInStock(@Valid @RequestBody List<OrderItemRequest> orderItemRequests) {
        return inventoryService.areInStock(orderItemRequests);
    }

    @PostMapping("/reserve")
    public BaseResponse reserveStock(@Valid @RequestBody List<OrderItemRequest> orderItemRequests) {
        return inventoryService.reserveStock(orderItemRequests);
    }
}
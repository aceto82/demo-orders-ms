package com.ortiz.inventory_service.controllers;

import com.ortiz.inventory_service.model.dtos.BaseResponse;
import com.ortiz.inventory_service.model.dtos.OrderItemRequest;
import com.ortiz.inventory_service.services.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{sku}")
    @PreAuthorize("hasRole('USER')")
    public boolean isInStock(@PathVariable String sku) {
        return inventoryService.isInStock(sku);
    }

    @PostMapping("/in-stock")
    @PreAuthorize("hasRole('USER')")
    public BaseResponse areInStock(@Valid @RequestBody List<OrderItemRequest> orderItemRequests) {
        return inventoryService.areInStock(orderItemRequests);
    }

    @PostMapping("/reserve")
    @PreAuthorize("hasRole('USER')")
    public BaseResponse reserveStock(@Valid @RequestBody List<OrderItemRequest> orderItemRequests) {
        return inventoryService.reserveStock(orderItemRequests);
    }
}
package com.ortiz.inventory_service.services;

import com.ortiz.inventory_service.exceptions.InsufficientStockException;
import com.ortiz.inventory_service.model.dtos.BaseResponse;
import com.ortiz.inventory_service.model.dtos.OrderItemRequest;
import com.ortiz.inventory_service.model.entites.Inventory;
import com.ortiz.inventory_service.repositories.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public boolean isInStock(String sku){
        var inventory = inventoryRepository.findBySku(sku);
        return inventory.filter(value -> value.getQuantity() != null && value.getQuantity() > 0).isPresent();
    }

    public BaseResponse areInStock(List<OrderItemRequest> items){
        var errorList = new ArrayList<String>();
        List<String> skus = items.stream().map(OrderItemRequest::getSku).toList();
        List<Inventory> inventoryList = inventoryRepository.findBySkuIn(skus);

        items.forEach(orderItem -> {
            if (!hasPositiveQuantity(orderItem)) {
                errorList.add(quantityError(orderItem));
                return;
            }
            var inventory = inventoryList.stream().filter(value -> value.getSku().equals(orderItem.getSku())).findFirst();
            if (inventory.isEmpty()) {
                errorList.add("No inventory found for sku: " + orderItem.getSku());
            }
            else if (inventory.get().getQuantity() == null || inventory.get().getQuantity() < orderItem.getQuantity()) {
                errorList.add("Insufficient quantity for sku: " + orderItem.getSku());
            }
        });

        return toBaseResponse(errorList);
    }

    /**
     * Atomically verifies availability and consumes stock. Every item is decremented through a
     * conditional UPDATE, so a partially fulfilled order would leave the database inconsistent;
     * throwing rolls the whole reservation back.
     */
    @Transactional
    public BaseResponse reserveStock(List<OrderItemRequest> items){
        var errorList = new ArrayList<String>();

        for (OrderItemRequest item : items) {
            if (!hasPositiveQuantity(item)) {
                errorList.add(quantityError(item));
                continue;
            }
            if (inventoryRepository.decrementStock(item.getSku(), item.getQuantity()) == 0) {
                errorList.add("Insufficient quantity for sku: " + item.getSku());
            }
        }

        if (!errorList.isEmpty()) {
            throw new InsufficientStockException(errorList);
        }
        return new BaseResponse(null);
    }

    private boolean hasPositiveQuantity(OrderItemRequest item){
        return item.getQuantity() != null && item.getQuantity() > 0;
    }

    private String quantityError(OrderItemRequest item){
        return "Quantity must be greater than zero for sku: " + item.getSku();
    }

    private BaseResponse toBaseResponse(List<String> errorList){
        return !errorList.isEmpty() ? new BaseResponse(errorList.toArray(new String[0])) : new BaseResponse(null);
    }
}
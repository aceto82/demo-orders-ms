package com.ortiz.inventory_service.services;

import com.ortiz.inventory_service.model.dtos.BaseResponse;
import com.ortiz.inventory_service.model.dtos.OrderItemRequest;
import com.ortiz.inventory_service.model.entites.Inventory;
import com.ortiz.inventory_service.repositories.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public boolean isInStock(String sku){
        var inventory = inventoryRepository.findBySku(sku);
        return inventory.filter(value -> value.getQuantity() > 0).isPresent();
    }

    public BaseResponse areInStock(List<OrderItemRequest> items){
        var errorList = new ArrayList<String>();
        List<String> skus = items.stream().map(OrderItemRequest::getSku).toList();
        List<Inventory> inventoryList = inventoryRepository.findBySkuIn(skus);

        items.forEach(orderItem -> {
            var inventory = inventoryList.stream().filter(value -> value.getSku().equals(orderItem.getSku())).findFirst();
            if (inventory.isEmpty()) {
                errorList.add("No inventory found for sku: " + orderItem.getSku());
            }
            else if (inventory.get().getQuantity() < orderItem.getQuantity()) {
                errorList.add("Insufficient quantity for sku: " + orderItem.getSku());
            }
        });

        return !errorList.isEmpty() ? new BaseResponse(errorList.toArray(new String[0])) : new BaseResponse(null);
    }


}

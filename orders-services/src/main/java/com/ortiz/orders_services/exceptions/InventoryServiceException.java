package com.ortiz.orders_services.exceptions;

public class InventoryServiceException extends RuntimeException {

    public InventoryServiceException(String message) {
        super(message);
    }
}
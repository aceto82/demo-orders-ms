package com.ortiz.inventory_service.exceptions;

import java.util.List;

public class InsufficientStockException extends RuntimeException {

    private final List<String> errorMessages;

    public InsufficientStockException(List<String> errorMessages) {
        super(String.join("; ", errorMessages));
        this.errorMessages = List.copyOf(errorMessages);
    }

    public List<String> getErrorMessages() {
        return errorMessages;
    }
}
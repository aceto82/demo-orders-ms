package com.ortiz.orders_services.exceptions;

import java.util.Arrays;

public class InsufficientStockException extends RuntimeException {

    private final String[] errorMessages;

    public InsufficientStockException(String[] errorMessages) {
        super(String.join("; ", Arrays.asList(errorMessages)));
        this.errorMessages = errorMessages;
    }

    public String[] getErrorMessages() {
        return errorMessages;
    }
}
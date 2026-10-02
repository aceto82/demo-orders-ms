package com.ortiz.orders_services.controller;

import com.ortiz.orders_services.exceptions.InsufficientStockException;
import com.ortiz.orders_services.exceptions.InventoryServiceException;
import com.ortiz.orders_services.model.dtos.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OrderExceptionHandler {

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public BaseResponse handleInsufficientStock(InsufficientStockException exception) {
        return new BaseResponse(exception.getErrorMessages());
    }

    @ExceptionHandler(InventoryServiceException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public BaseResponse handleInventoryUnavailable(InventoryServiceException exception) {
        return new BaseResponse(new String[]{exception.getMessage()});
    }
}
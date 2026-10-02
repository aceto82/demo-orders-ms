package com.ortiz.inventory_service.controllers;

import com.ortiz.inventory_service.exceptions.InsufficientStockException;
import com.ortiz.inventory_service.model.dtos.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class InventoryExceptionHandler {

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public BaseResponse handleInsufficientStock(InsufficientStockException exception) {
        return new BaseResponse(exception.getErrorMessages().toArray(new String[0]));
    }
}
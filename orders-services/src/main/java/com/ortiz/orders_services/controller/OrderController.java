package com.ortiz.orders_services.controller;

import com.ortiz.orders_services.model.dtos.OrderRequest;
import com.ortiz.orders_services.model.dtos.OrderResponse;
import com.ortiz.orders_services.services.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public String placeOrder(@Valid @RequestBody OrderRequest orderRequest) {
        this.orderService.placeOrder(orderRequest);
        return "Order placed";
    }

    @GetMapping
    public List<OrderResponse> getOrders(){
        return this.orderService.getOrders();
    }

}
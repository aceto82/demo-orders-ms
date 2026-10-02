package com.ortiz.orders_services.services;

import com.ortiz.orders_services.model.dtos.*;
import com.ortiz.orders_services.model.entities.Order;
import com.ortiz.orders_services.model.entities.OrderItems;
import com.ortiz.orders_services.repositories.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;

    public void placeOrder(OrderRequest orderRequest){
        // check for inventory
        BaseResponse result = this.webClientBuilder.build()
                .post()
                .uri("http://localhost:8083/api/inventory/in-stock")
                .bodyValue(orderRequest.getOrderItems())
                .retrieve()
                .bodyToMono(BaseResponse.class)
                .block();

        if(result == null || result.hasErrors()){
            throw new IllegalArgumentException("Invalid order request");
        }

        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());
        order.setOrderItems(orderRequest.getOrderItems().stream()
                .map(orderItemRequest -> mapToOrderItems(orderItemRequest,order))
                .toList()
        );
        this.orderRepository.save(order);
    }

    private OrderItems mapToOrderItems(OrderItemRequest orderItemRequest, Order order){
        return OrderItems.builder()
                .id(orderItemRequest.getId())
                .sku(orderItemRequest.getSku())
                .price(orderItemRequest.getPrice())
                .quantity(orderItemRequest.getQuantity())
                .order(order)
                .build();
    }

    public List<OrderResponse> getOrders(){
        List<Order> orders = this.orderRepository.findAll();
        return orders.stream().map(this::mapToOrderResponse).toList();
    }

    private OrderResponse mapToOrderResponse(Order order){
        return new OrderResponse(order.getId(), order.getOrderNumber(),
                order.getOrderItems().stream().map(
                        this::mapToOrderItemResponse
                ).toList()
        );
    }

    private OrderItemResponse mapToOrderItemResponse(OrderItems orderItems) {
        return new OrderItemResponse(orderItems.getId(), orderItems.getSku(), orderItems.getPrice(), orderItems.getQuantity());
    }

}

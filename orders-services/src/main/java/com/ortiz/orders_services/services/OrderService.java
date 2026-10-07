package com.ortiz.orders_services.services;

import com.ortiz.orders_services.events.OrderEvent;
import com.ortiz.orders_services.exceptions.InsufficientStockException;
import com.ortiz.orders_services.exceptions.InventoryServiceException;
import com.ortiz.orders_services.model.dtos.BaseResponse;
import com.ortiz.orders_services.model.dtos.OrderItemRequest;
import com.ortiz.orders_services.model.dtos.OrderItemResponse;
import com.ortiz.orders_services.model.dtos.OrderRequest;
import com.ortiz.orders_services.model.dtos.OrderResponse;
import com.ortiz.orders_services.model.entities.Order;
import com.ortiz.orders_services.model.entities.OrderItems;
import com.ortiz.orders_services.model.enums.OrderStatus;
import com.ortiz.orders_services.repositories.OrderRepository;
import com.ortiz.orders_services.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String INVENTORY_RESERVE_URL = "lb://inventory-service/api/inventory/reserve";
    private static final String ORDERS_TOPIC = "orders-topic";

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderResponse placeOrder(OrderRequest orderRequest){
        // Inventory verifies availability and consumes the stock atomically in a single call, so a
        // retry cannot place the same order twice against stock that was never consumed.
        BaseResponse result = reserveStock(orderRequest.getOrderItems());

        if (result.hasErrors()) {
            throw new InsufficientStockException(result.errorMessages());
        }

        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());
        order.setOrderItems(new ArrayList<>(orderRequest.getOrderItems().stream()
                .map(orderItemRequest -> mapToOrderItems(orderItemRequest, order))
                .toList())
        );
        var savedOrder = this.orderRepository.save(order);

        this.kafkaTemplate.send(ORDERS_TOPIC, JsonUtils.toJson(
                new OrderEvent(savedOrder.getOrderNumber(), savedOrder.getOrderItems().size(), OrderStatus.PLACED)
        ));

        return mapToOrderResponse(savedOrder);
    }

    private BaseResponse reserveStock(List<OrderItemRequest> orderItems) {
        return this.webClientBuilder.build()
                .post()
                .uri(INVENTORY_RESERVE_URL)
                .bodyValue(orderItems)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(BaseResponse.class)
                                .switchIfEmpty(Mono.error(new InventoryServiceException(
                                        "Inventory service returned an empty reservation response")));
                    }
                    if (response.statusCode().is4xxClientError()) {
                        return response.bodyToMono(BaseResponse.class)
                                .defaultIfEmpty(new BaseResponse(new String[]{"Inventory service rejected the order"}));
                    }
                    return Mono.error(new InventoryServiceException(
                            "Inventory service unavailable, status: " + response.statusCode().value()));
                })
                .onErrorMap(WebClientRequestException.class,
                        cause -> new InventoryServiceException("Inventory service unreachable: " + cause.getMessage()))
                .block();
    }

    private OrderItems mapToOrderItems(OrderItemRequest orderItemRequest, Order order){
        return OrderItems.builder()
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
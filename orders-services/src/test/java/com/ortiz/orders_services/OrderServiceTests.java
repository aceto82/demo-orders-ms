package com.ortiz.orders_services;

import com.ortiz.orders_services.exceptions.InsufficientStockException;
import com.ortiz.orders_services.exceptions.InventoryServiceException;
import com.ortiz.orders_services.model.dtos.OrderItemRequest;
import com.ortiz.orders_services.model.dtos.OrderRequest;
import com.ortiz.orders_services.model.entities.Order;
import com.ortiz.orders_services.repositories.OrderRepository;
import com.ortiz.orders_services.services.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderServiceTests {

    private final OrderRepository orderRepository = mock(OrderRepository.class);

    @Test
    @DisplayName("a placed order gets server-generated ids, never a client-supplied one")
    void placeOrderDoesNotCopyClientSuppliedIds() {
        orderServiceRespondingWith(HttpStatus.OK, "{\"errorMessages\":null}")
                .placeOrder(new OrderRequest(List.of(new OrderItemRequest("000001", 9.99, 2L))));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());

        Order saved = captor.getValue();
        assertThat(saved.getOrderNumber()).isNotBlank();
        assertThat(saved.getOrderItems()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isNull();
            assertThat(item.getSku()).isEqualTo("000001");
            assertThat(item.getPrice()).isEqualTo(9.99);
            assertThat(item.getQuantity()).isEqualTo(2L);
            assertThat(item.getOrder()).isSameAs(saved);
        });
    }

    @Test
    @DisplayName("an order rejected by inventory is not persisted")
    void placeOrderRejectsWhenInventoryReportsErrors() {
        OrderService orderService = orderServiceRespondingWith(HttpStatus.CONFLICT,
                "{\"errorMessages\":[\"Insufficient quantity for sku: 000001\"]}");

        assertThatThrownBy(() -> orderService.placeOrder(
                new OrderRequest(List.of(new OrderItemRequest("000001", 9.99, 2L)))))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient quantity for sku: 000001");

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unavailable inventory service is an outage, not a business rejection")
    void placeOrderFailsWhenInventoryServiceIsDown() {
        OrderService orderService = orderServiceRespondingWith(HttpStatus.INTERNAL_SERVER_ERROR, "boom");

        assertThatThrownBy(() -> orderService.placeOrder(
                new OrderRequest(List.of(new OrderItemRequest("000001", 9.99, 2L)))))
                .isInstanceOf(InventoryServiceException.class);

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("an empty 200 body is not read as a successful reservation")
    void placeOrderFailsOnEmptyInventoryBody() {
        OrderService orderService = new OrderService(orderRepository, WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build()))
                .build()
                .mutate());

        assertThatThrownBy(() -> orderService.placeOrder(
                new OrderRequest(List.of(new OrderItemRequest("000001", 9.99, 2L)))))
                .isInstanceOf(InventoryServiceException.class);

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unreachable inventory service is reported as unavailable, not as a server error")
    void placeOrderFailsWhenInventoryIsUnreachable() {
        OrderService orderService = new OrderService(orderRepository, WebClient.builder()
                .exchangeFunction(request -> Mono.error(new WebClientRequestException(
                        new java.net.ConnectException("Connection refused"),
                        HttpMethod.POST,
                        URI.create("http://localhost:8083/api/inventory/reserve"),
                        HttpHeaders.EMPTY)))
                .build()
                .mutate());

        assertThatThrownBy(() -> orderService.placeOrder(
                new OrderRequest(List.of(new OrderItemRequest("000001", 9.99, 2L)))))
                .isInstanceOf(InventoryServiceException.class)
                .hasMessageContaining("unreachable");

        verify(orderRepository, never()).save(any());
    }

    private OrderService orderServiceRespondingWith(HttpStatus status, String body) {
        ClientResponse response = ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(response))
                .build()
                .mutate();
        return new OrderService(orderRepository, builder);
    }
}
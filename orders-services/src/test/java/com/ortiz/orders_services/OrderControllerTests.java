package com.ortiz.orders_services;

import com.ortiz.orders_services.controller.OrderController;
import com.ortiz.orders_services.controller.OrderExceptionHandler;
import com.ortiz.orders_services.model.dtos.OrderRequest;
import com.ortiz.orders_services.services.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTests {

    private OrderService orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OrderController(orderService))
                .setControllerAdvice(new OrderExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a negative quantity is rejected before the order reaches inventory")
    void rejectsNegativeQuantity() throws Exception {
        mockMvc.perform(post("/api/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderItems":[{"sku":"000001","price":9.99,"quantity":-999999}]}"""))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).placeOrder(any());
    }

    @Test
    @DisplayName("a zero quantity is rejected")
    void rejectsZeroQuantity() throws Exception {
        mockMvc.perform(post("/api/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderItems":[{"sku":"000001","price":9.99,"quantity":0}]}"""))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).placeOrder(any());
    }

    @Test
    @DisplayName("a missing quantity is rejected")
    void rejectsMissingQuantity() throws Exception {
        mockMvc.perform(post("/api/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderItems":[{"sku":"000001","price":9.99}]}"""))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).placeOrder(any());
    }

    @Test
    @DisplayName("a blank sku is rejected")
    void rejectsBlankSku() throws Exception {
        mockMvc.perform(post("/api/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderItems":[{"sku":"  ","price":9.99,"quantity":1}]}"""))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).placeOrder(any());
    }

    @Test
    @DisplayName("an order without items is rejected")
    void rejectsEmptyOrderItems() throws Exception {
        mockMvc.perform(post("/api/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderItems":[]}"""))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).placeOrder(any());
    }

    @Test
    @DisplayName("a client-supplied id is ignored instead of crashing the request")
    void ignoresClientSuppliedId() throws Exception {
        mockMvc.perform(post("/api/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderItems":[{"id":42,"sku":"000001","price":9.99,"quantity":2}]}"""))
                .andExpect(status().isCreated());

        ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(orderService).placeOrder(captor.capture());

        var item = captor.getValue().getOrderItems().getFirst();
        assertThat(item.getSku()).isEqualTo("000001");
        assertThat(item.getQuantity()).isEqualTo(2L);
    }

    @Test
    @DisplayName("a valid order is accepted")
    void acceptsValidOrder() throws Exception {
        mockMvc.perform(post("/api/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderItems":[{"sku":"000001","price":9.99,"quantity":2}]}"""))
                .andExpect(status().isCreated());

        verify(orderService).placeOrder(any());
    }
}
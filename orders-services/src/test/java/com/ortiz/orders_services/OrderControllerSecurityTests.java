package com.ortiz.orders_services;

import com.ortiz.orders_services.config.SecurityConfig;
import com.ortiz.orders_services.controller.OrderController;
import com.ortiz.orders_services.services.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerSecurityTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderService orderService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    @WithMockUser(roles = "USER")
    void userCanListOrders() throws Exception {
        when(orderService.getOrders()).thenReturn(List.of());
        mockMvc.perform(get("/api/order")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "GUEST")
    void nonUserCannotListOrders() throws Exception {
        mockMvc.perform(get("/api/order")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "GUEST")
    void nonUserWithInvalidBodyCannotPlaceOrder() throws Exception {
        mockMvc.perform(post("/api/order").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}

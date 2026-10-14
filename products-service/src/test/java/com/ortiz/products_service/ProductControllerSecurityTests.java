package com.ortiz.products_service;

import com.ortiz.products_service.config.SecurityConfig;
import com.ortiz.products_service.services.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import(SecurityConfig.class)
class ProductControllerSecurityTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ProductService productService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAddProduct() throws Exception {
        mockMvc.perform(post("/api/product").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"S1\",\"name\":\"n\",\"price\":1.0}"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotAddProduct() throws Exception {
        mockMvc.perform(post("/api/product").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"S1\",\"name\":\"n\",\"price\":1.0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userWithInvalidBodyCannotAddProduct() throws Exception {
        mockMvc.perform(post("/api/product").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"name\":\"n\",\"price\":1.0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCanListProducts() throws Exception {
        mockMvc.perform(get("/api/product")).andExpect(status().isOk());
    }
}

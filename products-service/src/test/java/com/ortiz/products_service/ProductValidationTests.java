package com.ortiz.products_service;

import com.ortiz.products_service.controllers.ProductController;
import com.ortiz.products_service.controllers.ProductExceptionHandler;
import com.ortiz.products_service.services.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductValidationTests {

    private final ProductService productService = mock(ProductService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProductController(productService))
            .setControllerAdvice(new ProductExceptionHandler())
            .build();

    @Test
    void rejectsBlankSku() throws Exception {
        mockMvc.perform(post("/api/product").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"  \",\"name\":\"n\",\"price\":1.0}"))
                .andExpect(status().isBadRequest());
        verify(productService, never()).addProduct(any());
    }

    @Test
    void rejectsNonPositivePrice() throws Exception {
        mockMvc.perform(post("/api/product").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"S\",\"name\":\"n\",\"price\":0}"))
                .andExpect(status().isBadRequest());
    }
}

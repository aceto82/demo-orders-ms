package com.ortiz.inventory_service;

import com.ortiz.inventory_service.config.SecurityConfig;
import com.ortiz.inventory_service.controllers.InventoryController;
import com.ortiz.inventory_service.services.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
@Import(SecurityConfig.class)
class InventoryControllerSecurityTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InventoryService inventoryService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    @WithMockUser(roles = "GUEST")
    void nonUserCannotReserve() throws Exception {
        mockMvc.perform(post("/api/inventory/reserve")
                        .contentType(APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCanCheckStock() throws Exception {
        mockMvc.perform(get("/api/inventory/000001")).andExpect(status().isOk());
    }
}

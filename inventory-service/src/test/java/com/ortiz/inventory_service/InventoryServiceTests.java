package com.ortiz.inventory_service;

import com.ortiz.inventory_service.exceptions.InsufficientStockException;
import com.ortiz.inventory_service.model.dtos.BaseResponse;
import com.ortiz.inventory_service.model.dtos.OrderItemRequest;
import com.ortiz.inventory_service.model.entites.Inventory;
import com.ortiz.inventory_service.repositories.InventoryRepository;
import com.ortiz.inventory_service.services.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class InventoryServiceTests {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @BeforeEach
    void clearInventory() {
        inventoryRepository.deleteAll();
    }

    @Test
    @DisplayName("reserveStock consumes the ordered quantity")
    void reserveStockDecrementsStock() {
        givenStock("SKU-A", 10L);

        inventoryService.reserveStock(List.of(new OrderItemRequest("SKU-A", 3L)));

        assertThat(quantityOf("SKU-A")).isEqualTo(7L);
    }

    @Test
    @DisplayName("a negative quantity must not be treated as always-available")
    void reserveStockRejectsNegativeQuantityWithoutDecrementing() {
        givenStock("SKU-A", 10L);

        assertThatThrownBy(() -> inventoryService.reserveStock(List.of(new OrderItemRequest("SKU-A", -999999L))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(quantityOf("SKU-A")).isEqualTo(10L);
    }

    @Test
    @DisplayName("a zero quantity is not a valid order line")
    void reserveStockRejectsZeroQuantity() {
        givenStock("SKU-A", 10L);

        assertThatThrownBy(() -> inventoryService.reserveStock(List.of(new OrderItemRequest("SKU-A", 0L))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(quantityOf("SKU-A")).isEqualTo(10L);
    }

    @Test
    @DisplayName("a missing quantity is rejected instead of throwing an NPE")
    void reserveStockRejectsNullQuantity() {
        givenStock("SKU-A", 10L);

        assertThatThrownBy(() -> inventoryService.reserveStock(List.of(new OrderItemRequest("SKU-A", null))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(quantityOf("SKU-A")).isEqualTo(10L);
    }

    @Test
    @DisplayName("an unknown sku cannot be reserved")
    void reserveStockRejectsUnknownSku() {
        assertThatThrownBy(() -> inventoryService.reserveStock(List.of(new OrderItemRequest("SKU-MISSING", 1L))))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-MISSING");
    }

    @Test
    @DisplayName("stock is never consumed past zero")
    void reserveStockRejectsQuantityAboveAvailableStock() {
        givenStock("SKU-A", 2L);

        assertThatThrownBy(() -> inventoryService.reserveStock(List.of(new OrderItemRequest("SKU-A", 5L))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(quantityOf("SKU-A")).isEqualTo(2L);
    }

    @Test
    @DisplayName("one short sku rolls back the whole reservation")
    void reserveStockRollsBackAlreadyDecrementedItems() {
        givenStock("SKU-OK", 10L);
        givenStock("SKU-SHORT", 1L);

        assertThatThrownBy(() -> inventoryService.reserveStock(List.of(
                new OrderItemRequest("SKU-OK", 4L),
                new OrderItemRequest("SKU-SHORT", 5L)
        )))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-SHORT");

        assertThat(quantityOf("SKU-OK")).isEqualTo(10L);
        assertThat(quantityOf("SKU-SHORT")).isEqualTo(1L);
    }

    @Test
    @DisplayName("areInStock flags a negative quantity instead of passing the check")
    void areInStockRejectsNegativeQuantity() {
        givenStock("SKU-A", 10L);

        BaseResponse response = inventoryService.areInStock(List.of(new OrderItemRequest("SKU-A", -999999L)));

        assertThat(response.hasErrors()).isTrue();
        assertThat(response.errorMessages()[0]).contains("greater than zero");
    }

    @Test
    @DisplayName("areInStock accepts a quantity the stock can cover")
    void areInStockAcceptsAvailableQuantity() {
        givenStock("SKU-A", 10L);

        assertThat(inventoryService.areInStock(List.of(new OrderItemRequest("SKU-A", 4L))).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("a null stored quantity is out of stock, not an NPE")
    void isInStockHandlesNullStoredQuantity() {
        givenStock("SKU-NULL", null);

        assertThat(inventoryService.isInStock("SKU-NULL")).isFalse();
    }

    private void givenStock(String sku, Long quantity) {
        inventoryRepository.save(Inventory.builder().sku(sku).quantity(quantity).build());
    }

    private Long quantityOf(String sku) {
        return inventoryRepository.findBySku(sku).orElseThrow().getQuantity();
    }
}
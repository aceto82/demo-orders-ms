package com.ortiz.inventory_service.repositories;

import com.ortiz.inventory_service.model.entites.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory,Long> {
    Optional<Inventory> findBySku(String sku);

    List<Inventory> findBySkuIn(List<String> skus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Inventory i set i.quantity = i.quantity - :quantity where i.sku = :sku and i.quantity >= :quantity")
    int decrementStock(@Param("sku") String sku, @Param("quantity") Long quantity);
}
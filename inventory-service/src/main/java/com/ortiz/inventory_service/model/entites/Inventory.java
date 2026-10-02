package com.ortiz.inventory_service.model.entites;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "inventory")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Inventory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sku;
    private Long quantity;
}

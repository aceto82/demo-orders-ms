package com.ortiz.orders_services.repositories;

import com.ortiz.orders_services.model.entities.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}

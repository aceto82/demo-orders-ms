package com.ortiz.orders_services.events;

import com.ortiz.orders_services.model.enums.OrderStatus;

public record OrderEvent(String orderNumber, int itemsCount, OrderStatus orderStatus) {
}

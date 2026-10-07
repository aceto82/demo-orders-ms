package com.ortiz.notification_service.events;

import com.ortiz.notification_service.model.enums.OrderStatus;

public record OrderEvent(String orderNumber, int itemsCount, OrderStatus orderStatus) {
}

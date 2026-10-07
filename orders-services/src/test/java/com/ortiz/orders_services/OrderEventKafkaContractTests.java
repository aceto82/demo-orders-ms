package com.ortiz.orders_services;

import com.ortiz.orders_services.events.OrderEvent;
import com.ortiz.orders_services.model.enums.OrderStatus;
import com.ortiz.orders_services.utils.JsonUtils;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEventKafkaContractTests {

    private static final String ORDER_NUMBER = "e4352d01-71e5-46d9-bfb0-267a10896292";

    @Test
    @DisplayName("producer is configured to write raw JSON bytes, not a JSON-encoded string")
    void producerValueSerializerIsStringSerializer() throws IOException {
        assertThat(producerConfig().get("value-serializer")).isEqualTo(StringSerializer.class.getName());
    }

    @Test
    @DisplayName("the bytes the producer puts on orders-topic can be parsed by the notification-service")
    void wireFormatIsParsableByConsumer() throws Exception {
        String payload = JsonUtils.toJson(new OrderEvent(ORDER_NUMBER, 1, OrderStatus.PLACED));

        Serializer<String> serializer = configuredValueSerializer();
        byte[] wire = serializer.serialize("orders-topic", payload);

        String receivedByListener = new String(wire, StandardCharsets.UTF_8);
        OrderEvent event = JsonUtils.fromJson(receivedByListener, OrderEvent.class);

        assertThat(event.orderNumber()).isEqualTo(ORDER_NUMBER);
        assertThat(event.itemsCount()).isEqualTo(1);
        assertThat(event.orderStatus()).isEqualTo(OrderStatus.PLACED);
    }

    @SuppressWarnings("unchecked")
    private Serializer<String> configuredValueSerializer() throws Exception {
        String className = (String) configuredProducerProperties()
                .get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        Serializer<String> serializer = (Serializer<String>) Utils.newInstance(className, Serializer.class);
        serializer.configure(configuredProducerProperties(), false);
        return serializer;
    }

    private Map<String, Object> configuredProducerProperties() throws IOException {
        Map<String, Object> yamlProducer = producerConfig();
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, List.of("localhost:9092"));
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, yamlProducer.get("key-serializer"));
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, yamlProducer.get("value-serializer"));
        return properties;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> producerConfig() throws IOException {
        Path mainConfig = Path.of("src", "main", "resources", "application.yaml");
        try (InputStream in = Files.newInputStream(mainConfig)) {
            Map<String, Object> yaml = new Yaml().load(in);
            Map<String, Object> kafka = (Map<String, Object>) yaml.get("spring");
            Map<String, Object> kafkaConfig = (Map<String, Object>) kafka.get("kafka");
            return (Map<String, Object>) kafkaConfig.get("producer");
        }
    }
}

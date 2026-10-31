package com.moveinsync.hrsync.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.config.KafkaSettings;
import com.moveinsync.hrsync.domain.ChangeNotice;
import com.moveinsync.hrsync.domain.EventPublisher;
import com.moveinsync.hrsync.kafka.EmployeeChangeMessage;
import com.moveinsync.hrsync.metrics.SyncMeters;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "hr-sync.publisher", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    public static final String CORRELATION_HEADER = "correlationId";

    private final KafkaSettings settings;
    private final ObjectMapper objectMapper;
    private final SyncMeters meters;
    private final KafkaProducer<String, String> producer;
    private volatile Consumer<ChangeNotice> beforeAck = notice -> {
    };
    private volatile Consumer<ChangeNotice> afterAck = notice -> {
    };

    public KafkaEventPublisher(KafkaSettings settings, ObjectMapper objectMapper, SyncMeters meters) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.meters = meters;
        this.producer = new KafkaProducer<>(producerProperties(settings));
    }

    @Override
    public void publish(ChangeNotice notice) {
        long started = System.nanoTime();
        boolean acked = false;
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    settings.getTopic(),
                    EmployeeChangeMessage.key(notice),
                    json(notice)
            );
            if (notice.correlationId() != null && !notice.correlationId().isBlank()) {
                record.headers().add(CORRELATION_HEADER, notice.correlationId().getBytes(StandardCharsets.UTF_8));
            }
            var future = producer.send(record);
            beforeAck.accept(notice);
            future.get(settings.getDeliveryTimeout().toMillis(), TimeUnit.MILLISECONDS);
            acked = true;
            meters.kafkaSent(Duration.ofNanos(System.nanoTime() - started));
            afterAck.accept(notice);
        } catch (InterruptedException exception) {
            if (!acked) {
                meters.kafkaFailed();
            }
            Thread.currentThread().interrupt();
            throw new KafkaPublishException("kafka publish interrupted");
        } catch (TimeoutException | ExecutionException exception) {
            if (!acked) {
                meters.kafkaFailed();
            }
            throw new KafkaPublishException("kafka publish failed", exception);
        } catch (RuntimeException exception) {
            if (!acked) {
                meters.kafkaFailed();
            }
            if (exception instanceof KafkaPublishException) {
                throw exception;
            }
            throw new KafkaPublishException("kafka publish failed", exception);
        }
    }

    public void setBeforeAck(Consumer<ChangeNotice> beforeAck) {
        this.beforeAck = beforeAck == null ? notice -> {
        } : beforeAck;
    }

    public void setAfterAck(Consumer<ChangeNotice> afterAck) {
        this.afterAck = afterAck == null ? notice -> {
        } : afterAck;
    }

    @PreDestroy
    void close() {
        producer.close(settings.getDeliveryTimeout());
    }

    private String json(ChangeNotice notice) {
        try {
            return objectMapper.writeValueAsString(EmployeeChangeMessage.from(notice));
        } catch (JsonProcessingException exception) {
            throw new KafkaPublishException("kafka publish failed");
        }
    }

    static Properties producerProperties(KafkaSettings settings) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.toString(settings.getMaxBlock().toMillis()));
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Long.toString(settings.getDeliveryTimeout().toMillis()));
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Long.toString(settings.getRequestTimeout().toMillis()));
        properties.put(ProducerConfig.LINGER_MS_CONFIG, Long.toString(settings.getLinger().toMillis()));
        properties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, settings.getCompression());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "hr-sync");
        return properties;
    }
}

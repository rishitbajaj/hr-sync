package com.moveinsync.hrsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hr-sync.kafka")
public class KafkaSettings {

    private String bootstrapServers = "localhost:9092";

    private String topic = "hr-sync.employee-changes";

    private int partitions = 6;

    private short replicationFactor = 1;

    private Duration maxBlock = Duration.ofSeconds(1);

    private Duration deliveryTimeout = Duration.ofSeconds(3);

    private Duration requestTimeout = Duration.ofMillis(1500);

    private Duration linger = Duration.ofMillis(1);

    private int batchSize = 65536;

    private String compression = "lz4";

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getPartitions() {
        return partitions;
    }

    public void setPartitions(int partitions) {
        this.partitions = partitions;
    }

    public short getReplicationFactor() {
        return replicationFactor;
    }

    public void setReplicationFactor(short replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    public Duration getMaxBlock() {
        return maxBlock;
    }

    public void setMaxBlock(Duration maxBlock) {
        this.maxBlock = maxBlock;
    }

    public Duration getDeliveryTimeout() {
        return deliveryTimeout;
    }

    public void setDeliveryTimeout(Duration deliveryTimeout) {
        this.deliveryTimeout = deliveryTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getLinger() {
        return linger;
    }

    public void setLinger(Duration linger) {
        this.linger = linger;
    }

    public String getCompression() {
        return compression;
    }

    public void setCompression(String compression) {
        this.compression = compression;
    }
}

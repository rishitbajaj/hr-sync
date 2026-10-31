package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.domain.EventPublisher;
import com.moveinsync.hrsync.service.CompositeEventPublisher;
import com.moveinsync.hrsync.service.DeactivationEventConsumer;
import com.moveinsync.hrsync.service.KafkaEventPublisher;
import com.moveinsync.hrsync.service.LoggingEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class PublisherConfig {

    @Bean
    @Primary
    public EventPublisher eventPublisher(
            DeactivationEventConsumer effects,
            LoggingEventPublisher logging,
            ObjectProvider<KafkaEventPublisher> kafka,
            @Value("${hr-sync.publisher:logging}") String publisher
    ) {
        if ("kafka".equalsIgnoreCase(publisher)) {
            KafkaEventPublisher downstream = kafka.getIfAvailable();
            if (downstream == null) {
                throw new IllegalStateException("hr-sync.publisher=kafka but the Kafka publisher is not available");
            }
            return new CompositeEventPublisher(effects, downstream);
        }
        return new CompositeEventPublisher(effects, logging);
    }
}

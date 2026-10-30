package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.domain.BatchIngestor;
import com.moveinsync.hrsync.domain.ChangeStore;
import com.moveinsync.hrsync.domain.EventValidator;
import com.moveinsync.hrsync.domain.OfficeDirectory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        AuthSettings.class,
        IngestLimits.class,
        OutboxSettings.class,
        ReconciliationSettings.class,
        RetentionSettings.class
})
public class SyncConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public BatchIngestor batchIngestor(OfficeDirectory offices, ChangeStore changes) {
        return new BatchIngestor(new EventValidator(), offices, changes);
    }
}

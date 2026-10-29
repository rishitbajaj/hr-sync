package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.domain.BatchIngestor;
import com.moveinsync.hrsync.domain.EmployeeSyncStore;
import com.moveinsync.hrsync.domain.EventValidator;
import com.moveinsync.hrsync.domain.OfficeDirectory;
import com.moveinsync.hrsync.domain.RecordWork;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(IngestLimits.class)
public class SyncConfig {

    @Bean
    public BatchIngestor batchIngestor(OfficeDirectory offices, EmployeeSyncStore employees, RecordWork recordWork) {
        return new BatchIngestor(new EventValidator(), offices, employees, Clock.systemUTC(), recordWork);
    }
}

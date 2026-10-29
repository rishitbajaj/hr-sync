package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.domain.BatchIngestor;
import com.moveinsync.hrsync.domain.EmployeeSyncStore;
import com.moveinsync.hrsync.domain.EventValidator;
import com.moveinsync.hrsync.domain.OfficeDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class SyncConfig {

    @Bean
    public BatchIngestor batchIngestor(OfficeDirectory offices, EmployeeSyncStore employees) {
        return new BatchIngestor(new EventValidator(), offices, employees, Clock.systemUTC());
    }
}

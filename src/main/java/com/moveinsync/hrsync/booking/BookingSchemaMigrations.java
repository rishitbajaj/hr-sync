package com.moveinsync.hrsync.booking;

import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Migrations for the stand-in booking service. They live in schema {@code booking_svc}
 * with their own Flyway history so they are not part of the HR roster schema.
 */
@Component
public class BookingSchemaMigrations {

    public BookingSchemaMigrations(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("booking_svc")
                .defaultSchema("booking_svc")
                .createSchemas(true)
                .locations("classpath:db/booking")
                .table("flyway_schema_history")
                .load()
                .migrate();
    }
}

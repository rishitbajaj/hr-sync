package com.moveinsync.hrsync.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
public class DatabaseTimeouts implements BeanPostProcessor {

    private final DatabaseSettings settings;

    public DatabaseTimeouts(DatabaseSettings settings) {
        this.settings = settings;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource hikari && !hikari.isRunning()) {
            long connectionTimeout = settings.getConnectionTimeout().toMillis();
            hikari.setConnectionTimeout(connectionTimeout);
            hikari.setValidationTimeout(Math.min(1_000, Math.max(1, connectionTimeout - 1)));
            hikari.addDataSourceProperty(
                    "options",
                    "-c statement_timeout=" + settings.getStatementTimeout().toMillis()
            );
            hikari.addDataSourceProperty("socketTimeout", seconds(settings.getStatementTimeout()));
            hikari.addDataSourceProperty("connectTimeout", seconds(settings.getConnectionTimeout()));
        }
        return bean;
    }

    private static String seconds(java.time.Duration duration) {
        return Long.toString(Math.max(1, duration.toSeconds()));
    }
}

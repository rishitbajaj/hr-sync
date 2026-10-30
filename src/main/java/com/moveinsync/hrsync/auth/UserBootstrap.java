package com.moveinsync.hrsync.auth;

import com.moveinsync.hrsync.config.BootstrapSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * When the users table is empty and bootstrap environment variables are set, inserts one
 * operator and one admin. A later start sees those rows and does nothing. Passwords are
 * hashed with BCrypt and are never logged.
 */
@Component
@Order(0)
public class UserBootstrap implements ApplicationRunner {

    static final String OPERATOR_TENANT = "platform";

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapSettings settings;

    public UserBootstrap(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, BootstrapSettings settings) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        Integer count = jdbc.queryForObject("select count(*) from users", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        if (!settings.anyValueSet()) {
            return;
        }
        settings.requireComplete();
        insert(OPERATOR_TENANT, settings.getOperatorUsername().trim(), settings.getOperatorPassword(), UserRole.OPERATOR, null);
        insert(
                settings.getAdminTenant().trim(),
                settings.getAdminUsername().trim(),
                settings.getAdminPassword(),
                UserRole.ADMIN,
                null
        );
        log.info(
                "bootstrap created users operator={} admin={} adminTenant={}",
                settings.getOperatorUsername().trim(),
                settings.getAdminUsername().trim(),
                settings.getAdminTenant().trim()
        );
    }

    private void insert(String tenantId, String username, String password, UserRole role, String employeeId) {
        jdbc.update(
                """
                        insert into users (id, tenant_id, username, password_hash, role, employee_id)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                tenantId,
                username,
                passwordEncoder.encode(password),
                role.name(),
                employeeId
        );
    }
}

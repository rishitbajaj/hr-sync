package com.moveinsync.hrsync.auth;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("demo")
public class DemoUserSeeder implements ApplicationRunner {

    public static final String PASSWORD = "demo-password";
    public static final String OPERATOR = "operator";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public DemoUserSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        String hash = passwordEncoder.encode(PASSWORD);
        insert("platform", OPERATOR, hash, UserRole.OPERATOR, null);
        insert("demo-co", "admin", hash, UserRole.ADMIN, null);
        insert("demo-co", "employee", hash, UserRole.EMPLOYEE, "emp-demo");
    }

    private void insert(String tenantId, String username, String hash, UserRole role, String employeeId) {
        jdbc.update(
                """
                        insert into users (id, tenant_id, username, password_hash, role, employee_id)
                        values (?, ?, ?, ?, ?, ?)
                        on conflict (username) do nothing
                        """,
                UUID.randomUUID(),
                tenantId,
                username,
                hash,
                role.name(),
                employeeId
        );
    }
}

package com.moveinsync.hrsync.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.config.HttpRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class AccessRulesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void everyMappedEndpointHasAnExplicitRule() {
        List<String> uncovered = new ArrayList<>();
        for (RequestMappingInfoHandlerMapping mapping : context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (var entry : mapping.getHandlerMethods().entrySet()) {
                RequestMappingInfo info = entry.getKey();
                var handler = entry.getValue();
                List<String> paths = patterns(info);
                if (paths.isEmpty()) {
                    uncovered.add(handler.getBeanType().getSimpleName() + " has no path");
                    continue;
                }
                for (String pattern : paths) {
                    Set<String> methods = info.getMethodsCondition().getMethods().stream()
                            .map(Enum::name)
                            .collect(java.util.stream.Collectors.toSet());
                    if (methods.isEmpty()) {
                        if (!HttpRules.covers(null, pattern)) {
                            uncovered.add(handler.getBeanType().getSimpleName() + " " + pattern);
                        }
                        continue;
                    }
                    for (String method : methods) {
                        if (!HttpRules.covers(method, pattern)) {
                            uncovered.add(method + " " + pattern);
                        }
                    }
                }
            }
        }
        assertThat(uncovered).isEmpty();
    }

    @Test
    void employeeReadsFollowTheRoleAndHidePlaceholders() throws Exception {
        insert("roster", "emp-a", "ACTIVE", "Ada");
        insert("roster", "emp-b", "ACTIVE", "Bea");
        insert("roster", "emp-hidden", "PLACEHOLDER", "Hidden Person");
        insert("other", "emp-a", "ACTIVE", "Other Ada");

        ResponseEntity<String> anonymous = restTemplate.getForEntity("/employees/emp-a", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpEntity<Void> self = new HttpEntity<>(TestAccounts.employee(
                jdbc, passwordEncoder, restTemplate, objectMapper, "roster", "emp-a"
        ));
        ResponseEntity<String> own = restTemplate.exchange("/employees/emp-a", HttpMethod.GET, self, String.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody()).contains("\"employeeId\":\"emp-a\"").contains("ACTIVE");
        assertThat(own.getBody()).doesNotContain("Hidden Person").doesNotContain("Ada");

        ResponseEntity<String> otherEmployee = restTemplate.exchange("/employees/emp-b", HttpMethod.GET, self, String.class);
        assertThat(otherEmployee.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(otherEmployee.getBody()).doesNotContain("Bea").doesNotContain("emp-b");

        HttpEntity<Void> admin = new HttpEntity<>(TestAccounts.admin(
                jdbc, passwordEncoder, restTemplate, objectMapper, "roster"
        ));
        assertThat(restTemplate.exchange("/employees/emp-b", HttpMethod.GET, admin, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> placeholder = restTemplate.exchange(
                "/employees/emp-hidden",
                HttpMethod.GET,
                admin,
                String.class
        );
        assertThat(placeholder.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(placeholder.getBody()).doesNotContain("Hidden Person");

        ResponseEntity<String> wrongTenant = restTemplate.exchange(
                "/employees/emp-a?tenantId=other",
                HttpMethod.GET,
                admin,
                String.class
        );
        assertThat(wrongTenant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(wrongTenant.getBody()).doesNotContain("Other Ada");

        ResponseEntity<String> absentHere = restTemplate.exchange(
                "/employees/emp-only-other",
                HttpMethod.GET,
                admin,
                String.class
        );
        assertThat(absentHere.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        HttpEntity<Void> operator = new HttpEntity<>(TestAccounts.operator(
                jdbc, passwordEncoder, restTemplate, objectMapper
        ));
        ResponseEntity<String> unnamed = restTemplate.exchange("/employees/emp-a", HttpMethod.GET, operator, String.class);
        assertThat(unnamed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> named = restTemplate.exchange(
                "/employees/emp-a?tenantId=roster",
                HttpMethod.GET,
                operator,
                String.class
        );
        assertThat(named.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(named.getBody()).contains("roster");
    }

    private void insert(String tenant, String employeeId, String status, String name) {
        jdbc.update(
                """
                        insert into employee (
                            id, tenant_id, employee_id, name, email, phone, manager, office_code, shift,
                            pickup_point, special_assistance, hr_version, transport_version, status, updated_at
                        ) values (?, ?, ?, ?, ?, '+15550000001', 'M1', null, 'DAY', null, null, 1, 0, ?, ?)
                        """,
                UUID.randomUUID(),
                tenant,
                employeeId,
                name,
                employeeId + "@example.com",
                status,
                Timestamp.from(Instant.parse("2026-10-30T00:00:00Z"))
        );
    }

    private static List<String> patterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatterns().stream()
                    .map(pattern -> pattern.getPatternString())
                    .toList();
        }
        if (info.getPatternsCondition() != null) {
            return List.copyOf(info.getPatternsCondition().getPatterns());
        }
        return List.of();
    }
}

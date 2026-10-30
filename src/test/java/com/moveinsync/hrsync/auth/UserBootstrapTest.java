package com.moveinsync.hrsync.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "hr-sync.bootstrap.operator-username=boot-operator",
                "hr-sync.bootstrap.operator-password=boot-operator-secret",
                "hr-sync.bootstrap.admin-username=boot-admin",
                "hr-sync.bootstrap.admin-password=boot-admin-secret",
                "hr-sync.bootstrap.admin-tenant=boot-co"
        }
)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class UserBootstrapTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserBootstrap bootstrap;

    @Test
    void anEmptyTableIsSeededOnceAndARestartDoesNotChangeIt(CapturedOutput output) throws Exception {
        assertThat(count()).isEqualTo(2);
        assertThat(role("boot-operator")).isEqualTo("OPERATOR");
        assertThat(tenant("boot-operator")).isEqualTo("platform");
        assertThat(role("boot-admin")).isEqualTo("ADMIN");
        assertThat(tenant("boot-admin")).isEqualTo("boot-co");
        assertThat(hash("boot-admin")).doesNotContain("boot-admin-secret");
        assertThat(output).doesNotContain("boot-operator-secret").doesNotContain("boot-admin-secret");

        String token = login("boot-admin", "boot-admin-secret");
        assertThat(token).isNotBlank();

        bootstrap.run(null);

        assertThat(count()).isEqualTo(2);
        assertThat(login("boot-admin", "boot-admin-secret")).isNotBlank();
        assertThat(login("boot-operator", "boot-operator-secret")).isNotBlank();
        assertThat(output).doesNotContain("boot-operator-secret").doesNotContain("boot-admin-secret");
    }

    private String login(String username, String password) throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/token",
                Map.of("username", username, "password", password),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain(password);
        return objectMapper.readTree(response.getBody()).get("token").asText();
    }

    private int count() {
        Integer count = jdbc.queryForObject("select count(*) from users", Integer.class);
        return count == null ? 0 : count;
    }

    private String role(String username) {
        return jdbc.queryForObject("select role from users where username = ?", String.class, username);
    }

    private String tenant(String username) {
        return jdbc.queryForObject("select tenant_id from users where username = ?", String.class, username);
    }

    private String hash(String username) {
        return jdbc.queryForObject("select password_hash from users where username = ?", String.class, username);
    }
}

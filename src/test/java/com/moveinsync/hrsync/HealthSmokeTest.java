package com.moveinsync.hrsync;

import com.moveinsync.hrsync.api.HealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HealthSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<HealthResponse> response = restTemplate.getForEntity("/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new HealthResponse("UP"));
    }

    @Test
    void flywayAppliesSchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet tables = connection.getMetaData().getTables(null, "public", "%", new String[]{"TABLE"})) {
            assertThat(tableNames(tables)).contains(
                    "employee",
                    "office",
                    "outbox",
                    "processed_event",
                    "import_run"
            );
        }
    }

    private static Set<String> tableNames(ResultSet tables) throws Exception {
        Set<String> names = new HashSet<>();
        while (tables.next()) {
            names.add(tables.getString("TABLE_NAME"));
        }
        return names;
    }
}

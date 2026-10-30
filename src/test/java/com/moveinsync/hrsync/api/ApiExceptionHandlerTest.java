package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.config.DatabaseSettings;
import com.moveinsync.hrsync.observability.CorrelationFilter;
import com.moveinsync.hrsync.observability.CorrelationIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    private static final String SECRET = "secret@leak.example";
    private static final String SQL = "select email, phone from users where name = 'Ada'";
    private static final String TRACE = "trace-error-1";

    private static MockMvc mvc;

    @BeforeAll
    static void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new ApiExceptionHandler(new DatabaseSettings()))
                .addFilters(new CorrelationFilter())
                .build();
    }

    @Test
    void anUnexpectedExceptionHidesItsMessage() throws Exception {
        mvc.perform(get("/boom").header(CorrelationIds.HEADER, TRACE))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(CorrelationIds.HEADER, TRACE))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").value(TRACE))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQLException"))))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain(SECRET, SQL, "IllegalStateException", "java.sql", "stack");
                });
    }

    @Test
    void aDeadDatabaseIsUnavailableAndRetryable() throws Exception {
        mvc.perform(get("/down").header(CorrelationIds.HEADER, TRACE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(header().string(CorrelationIds.HEADER, TRACE))
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Database is unavailable"))
                .andExpect(jsonPath("$.correlationId").value(TRACE))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(SECRET, SQL, "CannotGetJdbcConnectionException", "08006"));
    }

    @Test
    void aConstraintFailureIsNotReportedAsUnavailable() throws Exception {
        mvc.perform(get("/constraint").header(CorrelationIds.HEADER, TRACE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(SECRET, "duplicate key", "users_pkey"));
    }

    @RestController
    static class Probe {

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException(SQL + " " + SECRET);
        }

        @GetMapping("/down")
        public String down() {
            throw new CannotGetJdbcConnectionException(
                    SQL + " " + SECRET,
                    new SQLException("connection refused", "08006")
            );
        }

        @GetMapping("/constraint")
        public String constraint() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint users_pkey " + SECRET);
        }
    }
}

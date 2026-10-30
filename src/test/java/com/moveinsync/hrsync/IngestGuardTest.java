package com.moveinsync.hrsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.api.IngestBodySizeFilter;
import com.moveinsync.hrsync.api.IngestExceptionHandler;
import com.moveinsync.hrsync.api.SyncBatchRequest;
import com.moveinsync.hrsync.api.SyncEventController;
import com.moveinsync.hrsync.api.SyncEventRequest;
import com.moveinsync.hrsync.api.SyncFieldsRequest;
import com.moveinsync.hrsync.config.IngestLimits;
import com.moveinsync.hrsync.domain.BatchIngestor;
import com.moveinsync.hrsync.domain.EventValidator;
import com.moveinsync.hrsync.domain.OfficeDirectory;
import com.moveinsync.hrsync.service.SyncEventService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IngestGuardTest {

    private static final String SECRET_EMAIL = "secret.person@leak.example";
    private static final String SECRET_PHONE = "+15558887777";
    private static final String SECRET_HOME = "88 Hidden Home Road";

    @Test
    void batchLargerThanTheCapIsRejectedBeforeAnyRecordIsApplied() throws Exception {
        InMemoryChangeStore store = new InMemoryChangeStore();
        IngestLimits limits = limits(2, 100_000);
        String body = new ObjectMapper().writeValueAsString(new SyncBatchRequest(List.of(
                event("e1", SECRET_EMAIL, SECRET_PHONE),
                event("e2", SECRET_EMAIL, SECRET_PHONE),
                event("e3", SECRET_EMAIL, SECRET_PHONE)
        )));

        String response = mvc(store, limits).perform(post("/sync/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BATCH_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("Batch exceeds the maximum of 2 events"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(store.rows).isEmpty();
        assertThat(response).doesNotContain(SECRET_EMAIL).doesNotContain(SECRET_PHONE).doesNotContain(SECRET_HOME);
    }

    @Test
    void bodyLargerThanTheCapIsRejected() throws Exception {
        InMemoryChangeStore store = new InMemoryChangeStore();
        IngestLimits limits = limits(100, 64);
        String token = "SECRET-BODY-TOKEN";
        String body = token.repeat(8);

        String response = mvc(store, limits).perform(post("/sync/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("REQUEST_TOO_LARGE"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("maximum of 64 bytes")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(store.rows).isEmpty();
        assertThat(response).doesNotContain(token);
    }

    @Test
    void chunkedBodyOverTheLimitIsRejectedWhileItIsRead() throws Exception {
        IngestLimits limits = limits(100, 32);
        String token = "SECRET-BODY-TOKEN";
        byte[] payload = token.repeat(6).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/sync/events") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(payload);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (filtered, servletResponse) -> {
            filtered.getInputStream().readAllBytes();
        };

        new IngestBodySizeFilter(limits).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).doesNotContain(token)
                .contains("REQUEST_TOO_LARGE");
    }

    private static MockMvc mvc(InMemoryChangeStore store, IngestLimits limits) {
        OfficeDirectory offices = (tenantId, officeCode) -> SampleHrBatch.OFFICE.equals(officeCode);
        SyncEventService service = new SyncEventService(
                new BatchIngestor(new EventValidator(), offices, store),
                new com.moveinsync.hrsync.metrics.SyncMeters(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
        );
        return MockMvcBuilders.standaloneSetup(new SyncEventController(service, limits))
                .setControllerAdvice(new IngestExceptionHandler())
                .addFilters(new IngestBodySizeFilter(limits))
                .build();
    }

    private static IngestLimits limits(int maxBatchSize, int maxBodyBytes) {
        IngestLimits limits = new IngestLimits();
        limits.setMaxBatchSize(maxBatchSize);
        limits.setMaxBodyBytes(maxBodyBytes);
        return limits;
    }

    private static SyncEventRequest event(String employeeId, String email, String phone) {
        return new SyncEventRequest(
                SampleHrBatch.TENANT,
                employeeId,
                1L,
                "CREATE",
                new SyncFieldsRequest("Ada", email, phone, "M1", SampleHrBatch.OFFICE, SECRET_HOME, null, null)
        );
    }

}

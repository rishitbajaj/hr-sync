package com.moveinsync.hrsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.auth.TestAccounts;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReconciliationBenchmarkTest {

    private static final int ROWS = 200_000;
    private static final long HEAP_CAP_BYTES = 256L * 1024 * 1024;
    private static final String TENANT = "recon-bench";
    private static final String OFFICE = "BLR";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void identicalExportDiffsTwoHundredThousandRowsUnderTheHeapCap() throws Exception {
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(HEAP_CAP_BYTES);
        if (!offices.existsByTenantIdAndCode(TENANT, OFFICE)) {
            offices.save(new OfficeEntity(UUID.randomUUID(), TENANT, OFFICE));
        }
        Path csv = Files.createTempFile("recon-bench-", ".csv");
        try {
            seedActive(ROWS);
            writeExport(csv, ROWS);
            System.gc();
            resetHeapPeaks();

            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            AtomicLong peakHeap = new AtomicLong(memory.getHeapMemoryUsage().getUsed());
            AtomicBoolean sampling = new AtomicBoolean(true);
            Thread sampler = new Thread(() -> {
                while (sampling.get()) {
                    peakHeap.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "reconciliation-heap-sampler");
            sampler.setDaemon(true);
            sampler.start();

            long started = System.nanoTime();
            JsonNode run = postCsv(csv);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            sampling.set(false);
            sampler.join(1_000);
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                    peakHeap.accumulateAndGet(pool.getPeakUsage().getUsed(), Math::max);
                }
            }

            System.out.printf(
                    "reconciliation benchmark rows=%d elapsedMs=%d peakHeapBytes=%d maxHeapBytes=%d%n",
                    ROWS,
                    elapsedMs,
                    peakHeap.get(),
                    Runtime.getRuntime().maxMemory()
            );

            assertThat(elapsedMs).isLessThan(60_000);
            assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(run.get("unchanged").asInt()).isEqualTo(ROWS);
            assertThat(run.get("toCreate").asInt()).isZero();
            assertThat(run.get("toUpdate").asInt()).isZero();
            assertThat(run.get("toDeactivate").asInt()).isZero();
            assertThat(run.get("missingCount").asInt()).isZero();
            assertThat(run.get("activeCount").asInt()).isEqualTo(ROWS);
            assertThat(run.get("exportRowCount").asInt()).isEqualTo(ROWS);
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    private JsonNode postCsv(Path csv) throws Exception {
        HttpHeaders headers = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, TENANT);
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/reconciliation/runs?tenantId=" + TENANT,
                new HttpEntity<>(new FileSystemResource(csv), headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("@example.com").doesNotContain("Pat");
        return objectMapper.readTree(response.getBody());
    }

    private void seedActive(int count) {
        Timestamp updatedAt = Timestamp.from(Instant.parse("2026-10-30T00:00:00Z"));
        String sql = """
                insert into employee (
                    id, tenant_id, employee_id, name, email, phone, manager, office_code, shift,
                    pickup_point, special_assistance, hr_version, transport_version, status, updated_at
                ) values (?, ?, ?, 'Pat', ?, '+15550000001', 'M1', 'BLR', 'DAY', null, null, 1, 0, 'ACTIVE', ?)
                """;
        int batch = 500;
        for (int start = 0; start < count; start += batch) {
            int size = Math.min(batch, count - start);
            int offset = start;
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    String employeeId = employeeId(offset + index);
                    statement.setObject(1, UUID.randomUUID());
                    statement.setString(2, TENANT);
                    statement.setString(3, employeeId);
                    statement.setString(4, employeeId + "@example.com");
                    statement.setTimestamp(5, updatedAt);
                }

                @Override
                public int getBatchSize() {
                    return size;
                }
            });
        }
    }

    private static void writeExport(Path csv, int rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("tenantId,employeeId,sourceVersion,name,email,phone,managerId,officeCode,shift");
            writer.newLine();
            for (int index = 0; index < rows; index++) {
                String employeeId = employeeId(index);
                writer.write(TENANT);
                writer.write(',');
                writer.write(employeeId);
                writer.write(",1,Pat,");
                writer.write(employeeId);
                writer.write("@example.com,+15550000001,M1,BLR,DAY");
                writer.newLine();
            }
        }
    }

    private static String employeeId(int index) {
        return "e%06d".formatted(index);
    }

    private static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }
}
